"""
LockSuite MDM — Instalador & Aprovisionador Portable
Herramienta visual para instalar y configurar LockSuite con todos los privilegios
(Device Owner, Accesibilidad, AppOps, Batería, etc.) con detección automática de cuentas.
"""

import os
import sys
import re
import shutil
import subprocess
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox
import customtkinter as ctk

# Configuración de tema CustomTkinter
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

PACKAGE_NAME = "com.ejemplo.locksuite"
ADMIN_RECEIVER = "com.ejemplo.locksuite/.receiver.DeviceAdminReceiver"
ACCESSIBILITY_SERVICE = "com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
LOGIN_ACTIVITY = "com.ejemplo.locksuite/.ui.auth.LoginActivity"


def get_adb_path():
    """Encuentra el binario de ADB (embebido en PyInstaller, local o en el sistema)"""
    # 1. Empaquetado en PyInstaller (_MEIPASS)
    if hasattr(sys, '_MEIPASS'):
        bundle_adb = os.path.join(sys._MEIPASS, "bin", "adb.exe")
        if os.path.isfile(bundle_adb):
            return bundle_adb

    # 2. Carpeta bin relativa al script
    local_bin_adb = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bin", "adb.exe")
    if os.path.isfile(local_bin_adb):
        return local_bin_adb

    # 3. C:\adb\adb.exe
    if os.path.isfile(r"C:\adb\adb.exe"):
        return r"C:\adb\adb.exe"

    # 4. PATH del sistema
    sys_adb = shutil.which("adb")
    if sys_adb:
        return sys_adb

    return "adb"


def run_adb_cmd(cmd_list, timeout=30):
    """Ejecuta un comando ADB y retorna (returncode, stdout, stderr)"""
    adb_bin = get_adb_path()
    full_cmd = [adb_bin] + cmd_list
    try:
        startupinfo = None
        if os.name == 'nt':
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startupinfo.wShowWindow = 0  # SW_HIDE

        process = subprocess.Popen(
            full_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            errors='replace',
            startupinfo=startupinfo,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        )
        stdout, stderr = process.communicate(timeout=timeout)
        return process.returncode, stdout.strip(), stderr.strip()
    except subprocess.TimeoutExpired:
        process.kill()
        return -1, "", "Timeout de ejecución"
    except Exception as e:
        return -1, "", str(e)


class LockSuiteInstallerApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("LockSuite MDM — Instalador & Aprovisionador")
        self.geometry("920, 740")
        self.minsize(840, 640)

        # Variables de estado
        self.apk_path_var = tk.StringVar()
        self.device_status_var = tk.StringVar(value="Buscando dispositivo...")
        self.device_info_var = tk.StringVar(value="")
        self.is_busy = False

        # Intentar pre-seleccionar APK si existe en ubicaciones típicas
        self.auto_discover_apk()

        # Construcción de la interfaz
        self.build_ui()

        # Iniciar sondeo de dispositivo en segundo plano
        self.after(500, self.start_device_monitor)

    def auto_discover_apk(self):
        possible_paths = [
            os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "app", "build", "outputs", "apk", "release", "app-release.apk")),
            os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "release-apk", "LockSuite_MDM_Release.apk")),
            os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "admin-backend", "public", "locksuite-latest.apk")),
            os.path.abspath(os.path.join(os.path.dirname(__file__), "app-release.apk")),
            os.path.abspath("app-release.apk"),
        ]
        for p in possible_paths:
            if os.path.isfile(p):
                self.apk_path_var.set(p)
                break

    def build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(2, weight=1)

        # ─── HEADER / ENCABEZADO ─────────────────────────────
        header_frame = ctk.CTkFrame(self, corner_radius=12, fg_color=("#1e293b", "#0f172a"))
        header_frame.grid(row=0, column=0, padx=16, pady=(16, 8), sticky="ew")
        header_frame.grid_columnconfigure(1, weight=1)

        logo_label = ctk.CTkLabel(
            header_frame,
            text="🛡️",
            font=ctk.CTkFont(size=36)
        )
        logo_label.grid(row=0, column=0, rowspan=2, padx=(16, 12), pady=12)

        title_label = ctk.CTkLabel(
            header_frame,
            text="LockSuite MDM — Aprovisionador Automático",
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color=("#f8fafc", "#f8fafc")
        )
        title_label.grid(row=0, column=1, sticky="w", pady=(12, 0))

        sub_label = ctk.CTkLabel(
            header_frame,
            text="Instalación, Device Owner, Accesibilidad y configuración de sistema vía ADB",
            font=ctk.CTkFont(size=12),
            text_color=("#94a3b8", "#94a3b8")
        )
        sub_label.grid(row=1, column=1, sticky="w", pady=(0, 12))

        # Botón de refresco rápido de ADB
        btn_refresh_adb = ctk.CTkButton(
            header_frame,
            text="🔄 Reintentar ADB",
            width=130,
            height=32,
            fg_color="#334155",
            hover_color="#475569",
            command=self.refresh_device_info_async
        )
        btn_refresh_adb.grid(row=0, column=2, rowspan=2, padx=16, pady=12)

        # ─── PANEL SUPERIOR (Dispositivo + Selector APK) ─────
        top_frame = ctk.CTkFrame(self, corner_radius=12)
        top_frame.grid(row=1, column=0, padx=16, pady=6, sticky="ew")
        top_frame.grid_columnconfigure(1, weight=1)

        # 1. Estado del Dispositivo
        lbl_dev_title = ctk.CTkLabel(top_frame, text="Dispositivo:", font=ctk.CTkFont(weight="bold"))
        lbl_dev_title.grid(row=0, column=0, padx=(16, 8), pady=(12, 6), sticky="w")

        self.lbl_device_badge = ctk.CTkLabel(
            top_frame,
            textvariable=self.device_status_var,
            font=ctk.CTkFont(weight="bold", size=13),
            text_color="#f59e0b"
        )
        self.lbl_device_badge.grid(row=0, column=1, padx=4, pady=(12, 6), sticky="w")

        self.lbl_device_details = ctk.CTkLabel(
            top_frame,
            textvariable=self.device_info_var,
            font=ctk.CTkFont(size=12),
            text_color="#94a3b8"
        )
        self.lbl_device_details.grid(row=0, column=2, padx=(4, 16), pady=(12, 6), sticky="e")

        # 2. Selector de archivo APK
        lbl_apk_title = ctk.CTkLabel(top_frame, text="Archivo APK:", font=ctk.CTkFont(weight="bold"))
        lbl_apk_title.grid(row=1, column=0, padx=(16, 8), pady=(6, 14), sticky="w")

        self.entry_apk = ctk.CTkEntry(
            top_frame,
            textvariable=self.apk_path_var,
            placeholder_text="Seleccioná el archivo LockSuite .apk...",
            height=36
        )
        self.entry_apk.grid(row=1, column=1, padx=4, pady=(6, 14), sticky="ew")

        btn_browse = ctk.CTkButton(
            top_frame,
            text="📂 Explorar...",
            width=120,
            height=36,
            command=self.browse_apk
        )
        btn_browse.grid(row=1, column=2, padx=(8, 16), pady=(6, 14), sticky="e")

        # ─── PANEL CENTRAL / LOGS Y PROGRESO ────────────────
        center_frame = ctk.CTkFrame(self, corner_radius=12)
        center_frame.grid(row=2, column=0, padx=16, pady=6, sticky="nsew")
        center_frame.grid_columnconfigure(0, weight=1)
        center_frame.grid_rowconfigure(2, weight=1)

        # Barra de estado y progreso
        prog_header = ctk.CTkFrame(center_frame, fg_color="transparent")
        prog_header.grid(row=0, column=0, padx=12, pady=(10, 4), sticky="ew")
        prog_header.grid_columnconfigure(0, weight=1)

        self.lbl_progress_status = ctk.CTkLabel(
            prog_header,
            text="Listo para iniciar.",
            font=ctk.CTkFont(weight="bold", size=13),
            text_color="#cbd5e1"
        )
        self.lbl_progress_status.grid(row=0, column=0, sticky="w")

        self.lbl_progress_pct = ctk.CTkLabel(
            prog_header,
            text="0%",
            font=ctk.CTkFont(weight="bold", size=13),
            text_color="#38bdf8"
        )
        self.lbl_progress_pct.grid(row=0, column=1, sticky="e")

        self.progress_bar = ctk.CTkProgressBar(center_frame, height=8, corner_radius=4)
        self.progress_bar.grid(row=1, column=0, padx=12, pady=(0, 8), sticky="ew")
        self.progress_bar.set(0.0)

        # Consola de Registro
        self.log_textbox = ctk.CTkTextbox(
            center_frame,
            font=ctk.CTkFont(family="Consolas", size=12),
            wrap="word",
            corner_radius=8,
            fg_color=("#0f172a", "#020617")
        )
        self.log_textbox.grid(row=2, column=0, padx=12, pady=(0, 12), sticky="nsew")

        # ─── PANEL INFERIOR (Acciones) ───────────────────────
        bottom_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="transparent")
        bottom_frame.grid(row=3, column=0, padx=16, pady=(6, 16), sticky="ew")
        bottom_frame.grid_columnconfigure(2, weight=1)

        # Botón para escanear solo cuentas
        self.btn_check_accounts = ctk.CTkButton(
            bottom_frame,
            text="👥 Verificar Cuentas",
            width=160,
            height=42,
            fg_color="#334155",
            hover_color="#475569",
            command=self.check_accounts_only_async
        )
        self.btn_check_accounts.grid(row=0, column=0, padx=(0, 8), pady=4)

        # Botón para limpiar logs
        btn_clear_log = ctk.CTkButton(
            bottom_frame,
            text="🧹 Limpiar Registro",
            width=140,
            height=42,
            fg_color="#1e293b",
            hover_color="#334155",
            command=self.clear_logs
        )
        btn_clear_log.grid(row=0, column=1, padx=4, pady=4)

        # Botón Principal de Instalación y Aprovisionamiento
        self.btn_install_full = ctk.CTkButton(
            bottom_frame,
            text="🚀 Instalar y Aprovisionar Todo",
            font=ctk.CTkFont(size=15, weight="bold"),
            height=46,
            fg_color="#2563eb",
            hover_color="#1d4ed8",
            command=self.start_full_provisioning_async
        )
        self.btn_install_full.grid(row=0, column=3, padx=(8, 0), pady=4, sticky="e")

        # Mensaje de bienvenida en la consola
        self.log("=================================================================", "cyan")
        self.log(" LockSuite MDM — Instalador & Aprovisionador Automático", "cyan")
        self.log(f" Motor ADB: {get_adb_path()}", "gray")
        self.log("=================================================================", "cyan")
        self.log("1. Conectá el celular por USB con Depuración USB habilitada.", "yellow")
        self.log("2. Seleccioná el archivo APK de LockSuite si no se detectó.", "yellow")
        self.log("3. Presioná 'Instalar y Aprovisionar Todo'.", "yellow")
        self.log("")

    # ─── UTILIDADES DE LOG Y ESTADO ─────────────────────────
    def log(self, message, color="white"):
        timestamp = time.strftime("[%H:%M:%S] ")
        self.log_textbox.configure(state="normal")
        self.log_textbox.insert("end", f"{timestamp}{message}\n")
        self.log_textbox.see("end")
        self.log_textbox.configure(state="disabled")

    def clear_logs(self):
        self.log_textbox.configure(state="normal")
        self.log_textbox.delete("1.0", "end")
        self.log_textbox.configure(state="disabled")

    def set_progress(self, text, percent):
        self.lbl_progress_status.configure(text=text)
        self.lbl_progress_pct.configure(text=f"{int(percent * 100)}%")
        self.progress_bar.set(percent)

    def set_busy(self, busy):
        self.is_busy = busy
        state = "disabled" if busy else "normal"
        self.btn_install_full.configure(state=state)
        self.btn_check_accounts.configure(state=state)
        self.entry_apk.configure(state=state)

    def browse_apk(self):
        filename = filedialog.askopenfilename(
            title="Seleccionar LockSuite APK",
            filetypes=[("Archivos APK", "*.apk"), ("Todos los archivos", "*.*")]
        )
        if filename:
            self.apk_path_var.set(filename)
            self.log(f"[OK] APK seleccionado: {os.path.basename(filename)} ({os.path.getsize(filename)/(1024*1024):.2f} MB)", "green")

    # ─── DETECCIÓN DE DISPOSITIVO ───────────────────────────
    def start_device_monitor(self):
        """Inicia sondeo periódico en segundo plano"""
        threading.Thread(target=self._monitor_device_loop, daemon=True).start()

    def _monitor_device_loop(self):
        while True:
            if not self.is_busy:
                self.check_device_status_sync()
            time.sleep(3)

    def refresh_device_info_async(self):
        threading.Thread(target=self.check_device_status_sync, daemon=True).start()

    def check_device_status_sync(self):
        ret, stdout, stderr = run_adb_cmd(["devices"], timeout=5)
        lines = stdout.splitlines()[1:] if stdout else []
        connected_devices = [l.split() for l in lines if len(l.split()) >= 2]

        if not connected_devices:
            self.device_status_var.set("🔴 Desconectado")
            self.lbl_device_badge.configure(text_color="#ef4444")
            self.device_info_var.set("Ningún celular detectado por ADB")
            return "disconnected", None

        serial, status = connected_devices[0][0], connected_devices[0][1]

        if status == "unauthorized":
            self.device_status_var.set("🟡 No Autorizado")
            self.lbl_device_badge.configure(text_color="#f59e0b")
            self.device_info_var.set(f"Aceptá la depuración USB en la pantalla ({serial})")
            return "unauthorized", serial

        if status == "device":
            # Obtener modelo y versión de Android
            _, model, _ = run_adb_cmd(["shell", "getprop", "ro.product.model"])
            _, android_ver, _ = run_adb_cmd(["shell", "getprop", "ro.build.version.release"])
            info_str = f"{model or 'Android'} (Android {android_ver or '?'}) — [{serial}]"
            self.device_status_var.set("🟢 Conectado")
            self.lbl_device_badge.configure(text_color="#10b981")
            self.device_info_var.set(info_str)
            return "device", serial

        self.device_status_var.set(f"⚪ {status.capitalize()}")
        self.lbl_device_badge.configure(text_color="#94a3b8")
        self.device_info_var.set(f"Estado: {status} ({serial})")
        return status, serial

    # ─── DETECCIÓN DE CUENTAS ───────────────────────────────
    def get_device_accounts(self):
        """Retorna una lista con las cuentas registradas en el celular"""
        ret, stdout, _ = run_adb_cmd(["shell", "dumpsys", "account"])
        accounts = []
        if ret == 0 and stdout:
            # Buscar patrones: Account {name=usuario@gmail.com, type=com.google}
            matches = re.findall(r'Account\s*\{\s*name=([^,\}]+),\s*type=([^,\}]+)\s*\}', stdout)
            for name, acc_type in matches:
                accounts.append({"name": name.strip(), "type": acc_type.strip()})
        return accounts

    def check_accounts_only_async(self):
        threading.Thread(target=self._check_accounts_worker, daemon=True).start()

    def _check_accounts_worker(self):
        self.set_busy(True)
        self.log("--- Verificando cuentas activas en el dispositivo ---", "cyan")
        status, _ = self.check_device_status_sync()
        if status != "device":
            self.log("[ERROR] El dispositivo no está listo o autorizado.", "red")
            messagebox.showerror("Dispositivo no listo", "Asegurate de que el celular esté conectado y con la depuración USB autorizada.")
            self.set_busy(False)
            return

        accounts = self.get_device_accounts()
        if accounts:
            self.log(f"[ALERTA] Se detectaron {len(accounts)} cuenta(s) activa(s):", "yellow")
            msg_list = []
            for idx, acc in enumerate(accounts, 1):
                self.log(f"   {idx}. {acc['name']} ({acc['type']})", "yellow")
                msg_list.append(f"• {acc['name']} ({acc['type']})")

            accounts_text = "\n".join(msg_list)
            messagebox.showwarning(
                "Cuentas Detectadas en el Celular",
                f"Se detectaron las siguientes cuentas:\n\n{accounts_text}\n\n"
                "⚠️ IMPORTANTE:\n"
                "Para asignar Device Owner, Android exige que NO haya cuentas configuradas.\n\n"
                "Por favor, eliminalas temporalmente en:\n"
                "Ajustes → Cuentas (o Administrar Cuentas).\n\n"
                "Podrás volver a agregarlas una vez completado el aprovisionamiento."
            )
        else:
            self.log("[OK] No se detectaron cuentas activas. Listo para asignar Device Owner.", "green")
            messagebox.showinfo("Cuentas OK", "✓ No hay cuentas activas en el dispositivo.\nListo para Device Owner.")

        self.set_busy(False)

    # ─── FLUJO COMPLETO DE INSTALACIÓN Y APROVISIONAMIENTO ──
    def start_full_provisioning_async(self):
        apk = self.apk_path_var.get().strip()
        if not apk or not os.path.isfile(apk):
            messagebox.showerror("APK no seleccionado", "Por favor seleccioná un archivo .apk válido antes de continuar.")
            return

        threading.Thread(target=self._full_provisioning_worker, args=(apk,), daemon=True).start()

    def _full_provisioning_worker(self, apk_path):
        self.set_busy(True)
        self.log("==========================================================", "cyan")
        self.log("🚀 INICIANDO SECUENCIA DE INSTALACIÓN Y APROVISIONAMIENTO", "cyan")
        self.log("==========================================================", "cyan")

        try:
            # ── PASO 1: Verificar Conexión ADB ────────────────
            self.set_progress("Paso 1/6: Verificando conexión...", 0.10)
            self.log("[1/6] Verificando estado del dispositivo ADB...", "white")

            status, serial = self.check_device_status_sync()
            if status == "disconnected":
                raise Exception("No hay ningún dispositivo conectado por USB. Conectalo y asegurate de tener los drivers ADB.")
            elif status == "unauthorized":
                raise Exception("El dispositivo figura como 'No Autorizado'. Mirá la pantalla del celular y aceptá la clave RSA.")
            elif status != "device":
                raise Exception(f"Estado de dispositivo no válido: {status}")

            self.log(f"   ✓ Dispositivo conectado: {self.device_info_var.get()}", "green")

            # ── PASO 2: Verificar Cuentas para Device Owner ───
            self.set_progress("Paso 2/6: Verificando cuentas activas...", 0.25)
            self.log("[2/6] Comprobando existencia de cuentas registradas...", "white")

            while True:
                accounts = self.get_device_accounts()
                if not accounts:
                    self.log("   ✓ Dispositivo limpio sin cuentas. Apto para Device Owner.", "green")
                    break

                self.log(f"   ⚠️ Se encontraron {len(accounts)} cuenta(s) activas:", "yellow")
                for acc in accounts:
                    self.log(f"      - {acc['name']} ({acc['type']})", "yellow")

                # Dialogo interactivo para el usuario
                response = messagebox.askretrycancel(
                    "Cuentas Detectadas — Atención Requerida",
                    f"Android no permite asignar Device Owner con cuentas activas.\n\n"
                    f"Cuentas encontradas:\n" +
                    "\n".join([f"• {a['name']}" for a in accounts]) +
                    "\n\n👉 Por favor, eliminalas en Ajustes → Cuentas en el celular.\n\n"
                    "Presioná 'Reintentar' cuando las hayas eliminado, o 'Cancelar' para detener el proceso."
                )

                if not response:
                    raise Exception("Proceso cancelado por el usuario debido a cuentas activas en el equipo.")

                self.log("   Reverificando cuentas tras acción del usuario...", "gray")
                time.sleep(1)

            # ── PASO 3: Instalar APK ──────────────────────────
            self.set_progress("Paso 3/6: Instalando APK de LockSuite...", 0.45)
            apk_filename = os.path.basename(apk_path)
            self.log(f"[3/6] Transfiriendo e instalando {apk_filename}...", "white")

            ret, stdout, stderr = run_adb_cmd(["install", "-r", "-g", apk_path], timeout=120)
            if ret != 0 or "Success" not in stdout:
                raise Exception(f"Fallo al instalar APK: {stdout} {stderr}")

            self.log("   ✓ LockSuite instalado exitosamente.", "green")

            # ── PASO 4: Asignar Device Owner ──────────────────
            self.set_progress("Paso 4/6: Asignando Device Owner...", 0.65)
            self.log(f"[4/6] Asignando administrador total (Device Owner): {ADMIN_RECEIVER}...", "white")

            ret, stdout, stderr = run_adb_cmd(["shell", "dpm", "set-device-owner", ADMIN_RECEIVER], timeout=30)
            output_combined = f"{stdout}\n{stderr}".strip()

            if "Success" in output_combined:
                self.log("   ✓ Device Owner asignado exitosamente.", "green")
            elif "already some accounts" in output_combined:
                raise Exception("Error: Todavía quedan cuentas activas en el dispositivo. No se pudo asignar Device Owner.")
            elif "already set" in output_combined or "is already the device owner" in output_combined:
                self.log("   ✓ LockSuite ya era Device Owner previamente.", "green")
            else:
                self.log(f"   ⚠️ Respuesta DPM: {output_combined}", "yellow")
                if ret != 0:
                    raise Exception(f"No se pudo asignar Device Owner: {output_combined}")

            # ── PASO 5: Permisos de Sistema y Batería ─────────
            self.set_progress("Paso 5/6: Otorgando permisos de sistema...", 0.80)
            self.log("[5/6] Concediendo permisos avanzados (AppOps, Overlay, Batería)...", "white")

            # AppOps: Restricted Settings (Android 13+)
            run_adb_cmd(["shell", "appops", "set", PACKAGE_NAME, "ACCESS_RESTRICTED_SETTINGS", "allow"])
            # AppOps: Usage Stats
            run_adb_cmd(["shell", "appops", "set", PACKAGE_NAME, "GET_USAGE_STATS", "allow"])
            # AppOps: System Alert Window
            run_adb_cmd(["shell", "appops", "set", PACKAGE_NAME, "SYSTEM_ALERT_WINDOW", "allow"])
            # Batería Whitelist
            run_adb_cmd(["shell", "dumpsys", "deviceidle", "whitelist", f"+{PACKAGE_NAME}"])

            self.log("   ✓ Permisos de sistema concedidos (AppOps + Batería Doze Whitelist).", "green")

            # ── PASO 6: Activar Servicio de Accesibilidad ──────
            self.set_progress("Paso 6/6: Habilitando Accesibilidad...", 0.90)
            self.log(f"[6/6] Activando Servicio de Accesibilidad: {ACCESSIBILITY_SERVICE}...", "white")

            run_adb_cmd(["shell", "settings", "put", "secure", "enabled_accessibility_services", ACCESSIBILITY_SERVICE])
            run_adb_cmd(["shell", "settings", "put", "secure", "accessibility_enabled", "1"])

            # Verificación de binding
            time.sleep(1)
            _, a11y_dump, _ = run_adb_cmd(["shell", "dumpsys", "accessibility"])
            if "LockSuite" in a11y_dump or ACCESSIBILITY_SERVICE in a11y_dump:
                self.log("   ✓ Servicio de Accesibilidad activado y enlazado.", "green")
            else:
                self.log("   ✓ Comando de Accesibilidad enviado.", "green")

            # ── PASO FINAL: Abrir Aplicación ──────────────────
            self.log("Iniciando pantalla principal de LockSuite...", "white")
            run_adb_cmd(["shell", "am", "start", "-n", LOGIN_ACTIVITY])

            self.set_progress("¡Completado con Éxito!", 1.0)
            self.log("==========================================================", "green")
            self.log("🎉 ¡APROVISIONAMIENTO COMPLETADO EXITOSAMENTE!", "green")
            self.log("==========================================================", "green")
            self.log("• Device Owner: ACTIVO", "green")
            self.log("• Accesibilidad: ACTIVA", "green")
            self.log("• Permisos de Sistema: CONCEDIDOS", "green")
            self.log("👉 Ya podés volver a agregar tus cuentas en el celular.", "cyan")

            messagebox.showinfo(
                "¡Instalación Exitosa!",
                "✓ LockSuite MDM se instaló y aprovisionó con éxito.\n\n"
                "• Device Owner: Configurado\n"
                "• Accesibilidad: Habilitada\n"
                "• Permisos de fondo y batería: Concedidos\n\n"
                "¡Ya podés volver a agregar tus cuentas de Google en el celular!"
            )

        except Exception as e:
            self.set_progress("Error durante la instalación", 0.0)
            self.log(f"\n[ERROR CRÍTICO] {str(e)}", "red")
            messagebox.showerror("Error de Aprovisionamiento", str(e))

        finally:
            self.set_busy(False)


if __name__ == "__main__":
    app = LockSuiteInstallerApp()
    app.mainloop()
