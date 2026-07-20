/**
 * LockSuite WebADB Client
 * Standalone WebUSB ADB implementation for Web Browsers.
 */

class WebADB {
  constructor() {
    this.device = null;
    this.epIn = null;
    this.epOut = null;
    this.interfaceNumber = null;
    this.session = null;
    this.rsaKeys = null;
  }

  static isSupported() {
    return 'usb' in navigator;
  }

  async requestDevice() {
    if (!WebADB.isSupported()) {
      throw new Error('Tu navegador no soporta WebUSB. Por favor usa Google Chrome, Microsoft Edge o Brave.');
    }
    const filters = [{ classCode: 255, subclassCode: 42, protocolCode: 1 }];
    this.device = await navigator.usb.requestDevice({ filters });
    return this.device;
  }

  async connect() {
    if (!this.device) {
      throw new Error('No se ha seleccionado ningún dispositivo USB.');
    }
    await this.device.open();
    if (this.device.configuration === null) {
      await this.device.selectConfiguration(1);
    }

    // Find ADB Interface (Class 255, Subclass 42, Protocol 1)
    let adbInterface = null;
    for (const iface of this.device.configuration.interfaces) {
      for (const alt of iface.alternates) {
        if (alt.interfaceClass === 255 && alt.interfaceSubclass === 42 && alt.interfaceProtocol === 1) {
          adbInterface = iface;
          break;
        }
      }
      if (adbInterface) break;
    }

    if (!adbInterface) {
      throw new Error('No se encontró la interfaz de depuración ADB en el dispositivo. Asegúrate de activar "Depuración USB".');
    }

    this.interfaceNumber = adbInterface.interfaceNumber;
    await this.device.claimInterface(this.interfaceNumber);

    const endpoints = adbInterface.alternates[0].endpoints;
    this.epIn = endpoints.find(e => e.direction === 'in').endpointNumber;
    this.epOut = endpoints.find(e => e.direction === 'out').endpointNumber;

    await this.handshake();
  }

  async handshake() {
    // Send CNXN packet
    const banner = new TextEncoder().encode('host::LockSuiteWebADB\0');
    await this.sendPacket('CNXN', 0x01000000, 1048576, banner);

    let packet = await this.readPacket();
    if (packet.command === 'AUTH') {
      // ADB Authentication required
      const keyPair = await this.getOrCreateRSAKey();
      const signedData = await this.signAuthChallenge(keyPair.privateKey, packet.data);
      await this.sendPacket('AUTH', 2, 0, signedData);

      packet = await this.readPacket();
      if (packet.command === 'AUTH') {
        // Send Public Key
        const pubKeyStr = await this.exportRSAPublicKey(keyPair.publicKey);
        const pubKeyData = new TextEncoder().encode(pubKeyStr + '\0');
        await this.sendPacket('AUTH', 3, 0, pubKeyData);
        packet = await this.readPacket();
      }
    }

    if (packet.command !== 'CNXN') {
      throw new Error('Error en el protocolo ADB durante la conexión.');
    }
  }

  async sendPacket(commandStr, arg0, arg1, data = new Uint8Array(0)) {
    const cmd = this.strToUint32(commandStr);
    const magic = (cmd ^ 0xFFFFFFFF) >>> 0;
    const len = data.length;
    let checksum = 0;
    for (let i = 0; i < len; i++) {
      checksum = (checksum + data[i]) & 0xFFFFFFFF;
    }

    const header = new ArrayBuffer(24);
    const view = new DataView(header);
    view.setUint32(0, cmd, true);
    view.setUint32(4, arg0, true);
    view.setUint32(8, arg1, true);
    view.setUint32(12, len, true);
    view.setUint32(16, checksum, true);
    view.setUint32(20, magic, true);

    await this.device.transferOut(this.epOut, header);
    if (len > 0) {
      await this.device.transferOut(this.epOut, data.buffer);
    }
  }

  async readPacket() {
    const res = await this.device.transferIn(this.epIn, 24);
    if (res.status !== 'ok' || res.data.byteLength < 24) {
      throw new Error('Error al recibir paquete ADB desde el USB.');
    }
    const view = new DataView(res.data.buffer);
    const cmd = this.uint32ToStr(view.getUint32(0, true));
    const arg0 = view.getUint32(4, true);
    const arg1 = view.getUint32(8, true);
    const len = view.getUint32(12, true);

    let data = new Uint8Array(0);
    if (len > 0) {
      const dataRes = await this.device.transferIn(this.epIn, len);
      data = new Uint8Array(dataRes.data.buffer);
    }

    return { command: cmd, arg0, arg1, data };
  }

  async shell(command) {
    const service = new TextEncoder().encode(`shell:${command}\0`);
    const localId = 1;
    await this.sendPacket('OPEN', localId, 0, service);

    let packet = await this.readPacket();
    if (packet.command !== 'OKAY') {
      throw new Error(`Falló abrir shell ADB para: ${command}`);
    }

    const remoteId = packet.arg0;
    let output = '';

    while (true) {
      packet = await this.readPacket();
      if (packet.command === 'WRTE') {
        output += new TextDecoder().decode(packet.data);
        await this.sendPacket('OKAY', localId, remoteId);
      } else if (packet.command === 'CLSE') {
        await this.sendPacket('CLSE', localId, remoteId);
        break;
      }
    }

    return output;
  }

  async getDeviceAccounts() {
    try {
      const output = await this.shell('dumpsys account');
      const accounts = [];
      const accountRegex = /Account\s*\{?\s*name=([^,\}\s]+)[^}]*type=([^\}\s]+)/gi;
      let match;
      while ((match = accountRegex.exec(output)) !== null) {
        const name = match[1].trim();
        const type = match[2].trim();
        if (name && !accounts.some(a => a.name === name)) {
          accounts.push({ name, type });
        }
      }
      
      if (accounts.length === 0) {
        const altRegex = /name=([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/g;
        while ((match = altRegex.exec(output)) !== null) {
          const name = match[1];
          if (!accounts.some(a => a.name === name)) {
            accounts.push({ name, type: 'com.google' });
          }
        }
      }

      return accounts;
    } catch (e) {
      console.warn('Error fetching accounts via dumpsys:', e);
      return [];
    }
  }

  async installApk(apkBlob, onProgress) {
    onProgress('Preparando APK para instalación...', 10);

    const localId = 2;
    const path = '/data/local/tmp/locksuite.apk';
    const syncService = new TextEncoder().encode(`sync:\0`);
    await this.sendPacket('OPEN', localId, 0, syncService);

    let packet = await this.readPacket();
    if (packet.command !== 'OKAY') {
      throw new Error('No se pudo abrir el servicio ADB sync para subir la aplicación.');
    }
    const remoteId = packet.arg0;

    const sendHeader = new Uint8Array(4 + 4 + path.length + 4);
    const sendView = new DataView(sendHeader.buffer);
    sendView.setUint32(0, this.strToUint32('SEND'), true);
    sendView.setUint32(4, path.length, true);
    sendHeader.set(new TextEncoder().encode(path), 8);
    sendView.setUint32(8 + path.length, 0644, true);

    await this.sendPacket('WRTE', localId, remoteId, sendHeader);
    packet = await this.readPacket();

    const buffer = await apkBlob.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    const chunkSize = 64 * 1024;
    const total = bytes.length;

    for (let offset = 0; offset < total; offset += chunkSize) {
      const chunk = bytes.subarray(offset, offset + chunkSize);
      const dataHeader = new Uint8Array(8 + chunk.length);
      const dv = new DataView(dataHeader.buffer);
      dv.setUint32(0, this.strToUint32('DATA'), true);
      dv.setUint32(4, chunk.length, true);
      dataHeader.set(chunk, 8);

      await this.sendPacket('WRTE', localId, remoteId, dataHeader);
      packet = await this.readPacket();

      const progress = Math.min(80, 10 + Math.round((offset / total) * 70));
      onProgress(`Transfiriendo archivo... ${Math.round((offset / total) * 100)}%`, progress);
    }

    const doneHeader = new Uint8Array(8);
    const dvDone = new DataView(doneHeader.buffer);
    dvDone.setUint32(0, this.strToUint32('DONE'), true);
    dvDone.setUint32(4, Math.floor(Date.now() / 1000), true);

    await this.sendPacket('WRTE', localId, remoteId, doneHeader);
    packet = await this.readPacket();
    await this.sendPacket('CLSE', localId, remoteId);

    onProgress('Instalando LockSuite en el sistema...', 85);
    const installResult = await this.shell(`pm install -r ${path}`);
    await this.shell(`rm -f ${path}`);

    if (installResult.includes('Failure')) {
      throw new Error(`Error durante la instalación: ${installResult}`);
    }

    onProgress('Aplicando permisos de Device Owner...', 92);
    const doResult = await this.shell('dpm set-device-owner com.ejemplo.locksuite/.receiver.DeviceAdminReceiver');

    onProgress('Habilitando Servicios de Accesibilidad...', 97);
    await this.shell('settings put secure enabled_accessibility_services com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService');
    await this.shell('settings put secure accessibility_enabled 1');

    onProgress('¡Instalación completada con éxito!', 100);
    return doResult;
  }

  strToUint32(str) {
    return (str.charCodeAt(0) | (str.charCodeAt(1) << 8) | (str.charCodeAt(2) << 16) | (str.charCodeAt(3) << 24)) >>> 0;
  }

  uint32ToStr(num) {
    return String.fromCharCode(num & 0xFF, (num >> 8) & 0xFF, (num >> 16) & 0xFF, (num >> 24) & 0xFF);
  }

  async getOrCreateRSAKey() {
    return await crypto.subtle.generateKey(
      {
        name: 'RSASSA-PKCS1-v1_5',
        modulusLength: 2048,
        publicExponent: new Uint8Array([0x01, 0x00, 0x01]),
        hash: 'SHA-1'
      },
      true,
      ['sign', 'verify']
    );
  }

  async signAuthChallenge(privateKey, data) {
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', privateKey, data);
    return new Uint8Array(signature);
  }

  async exportRSAPublicKey(publicKey) {
    const exported = await crypto.subtle.exportKey('spki', publicKey);
    return btoa(String.fromCharCode(...new Uint8Array(exported)));
  }
}

window.WebADB = WebADB;
