import os
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors

def generate_pdf():
    pdf_path = r"C:\Users\israe\Downloads\Guia_Inscripcion_MDM.pdf"
    
    # Ensure Downloads directory exists (usually does)
    os.makedirs(os.path.dirname(pdf_path), exist_ok=True)
    
    doc = SimpleDocTemplate(pdf_path, pagesize=letter,
                            rightMargin=45, leftMargin=45,
                            topMargin=45, bottomMargin=40)
    story = []
    
    styles = getSampleStyleSheet()
    
    # Custom styles
    title_style = ParagraphStyle(
        'DocTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=22,
        leading=26,
        textColor=colors.HexColor('#1a365d'),
        spaceAfter=15
    )
    
    h1_style = ParagraphStyle(
        'Heading1',
        parent=styles['Heading1'],
        fontName='Helvetica-Bold',
        fontSize=15,
        leading=19,
        textColor=colors.HexColor('#2c5282'),
        spaceBefore=14,
        spaceAfter=8,
        keepWithNext=True
    )
    
    h2_style = ParagraphStyle(
        'Heading2',
        parent=styles['Heading2'],
        fontName='Helvetica-Bold',
        fontSize=11,
        leading=15,
        textColor=colors.HexColor('#2b6cb0'),
        spaceBefore=9,
        spaceAfter=4,
        keepWithNext=True
    )
    
    body_style = ParagraphStyle(
        'Body',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=9.5,
        leading=13.5,
        textColor=colors.HexColor('#2d3748'),
        spaceAfter=6
    )

    bullet_style = ParagraphStyle(
        'Bullet',
        parent=body_style,
        leftIndent=15,
        firstLineIndent=-10,
        spaceAfter=4
    )

    code_style = ParagraphStyle(
        'Code',
        parent=styles['Normal'],
        fontName='Courier',
        fontSize=9,
        leading=13,
        textColor=colors.HexColor('#b7791f'),
        backColor=colors.HexColor('#f7fafc'),
        borderColor=colors.HexColor('#e2e8f0'),
        borderWidth=1,
        borderPadding=8,
        spaceBefore=6,
        spaceAfter=6
    )

    # Document Header
    story.append(Paragraph("Guía de Inscripción Automática MDM", title_style))
    story.append(Paragraph("Implementación de Zero-Touch (ZTE), Samsung Knox (KME) y Métodos Manuales para LockSuite", ParagraphStyle('Subtitle', parent=body_style, fontName='Helvetica-Oblique', fontSize=10.5, textColor=colors.HexColor('#4a5568'), spaceAfter=18)))
    story.append(Spacer(1, 5))

    # Clarification section
    story.append(Paragraph("1. Diferencia Crítica: Samsung Knox (KME) vs. Zero-Touch (ZTE)", h1_style))
    story.append(Paragraph("<b>¿Por qué no es posible auto-inscribir equipos no-Samsung en Zero-Touch usando otro celular?</b>", h2_style))
    story.append(Paragraph("El programa oficial <b>Google Zero-Touch Enrollment (ZTE)</b> es un canal corporativo cerrado por razones de seguridad antirrobo. La consola y el registro están restringidos:", body_style))
    story.append(Paragraph("- Los dispositivos no-Samsung deben ser adquiridos obligatoriamente a través de un <b>distribuidor o reseller autorizado de Google</b> (carriers oficiales, marcas directo, etc.).", bullet_style))
    story.append(Paragraph("- Es el distribuidor quien asocia el IMEI o número de serie a tu cuenta de cliente de Zero-Touch en el momento de la venta. <b>No existe ninguna app o mecanismo de auto-iniciación</b> por parte del cliente.", bullet_style))
    story.append(Paragraph("Por el contrario, <b>Samsung Knox Mobile Enrollment (KME)</b> ofrece una herramienta única llamada <b>Knox Deployment App</b> (disponible en Google Play Store). Esta app te permite usar cualquier celular para escanear y registrar equipos Samsung tú mismo a tu cuenta, vía NFC o Bluetooth, sin pasar por el distribuidor.", body_style))
    
    story.append(Spacer(1, 5))

    # Alternate methods
    story.append(Paragraph("2. Alternativas de Aprovisionamiento Manual para No-Samsung", h1_style))
    story.append(Paragraph("Para los equipos no-Samsung que no se hayan comprado mediante un partner Zero-Touch, Android Enterprise provee tres métodos nativos para forzar el registro de LockSuite como Device Owner sin usar cables:", body_style))
    
    story.append(Paragraph("<b>A. Método del Lector de Códigos QR (Recomendado)</b>", h2_style))
    story.append(Paragraph("1. Restablece el dispositivo no-Samsung de fábrica.", bullet_style))
    story.append(Paragraph("2. En la pantalla inicial de bienvenida, <b>toca 6 veces consecutivas en el mismo lugar de la pantalla</b>. Esto iniciará el asistente de QR oculto de Android.", bullet_style))
    story.append(Paragraph("3. Conéctate a una red Wi-Fi cuando el asistente lo solicite, y la cámara se abrirá de inmediato.", bullet_style))
    story.append(Paragraph("4. Escanea el código QR de aprovisionamiento de LockSuite. Android descargará la app del servidor y se configurará como Device Owner en 1 paso.", bullet_style))
    
    story.append(Paragraph("<b>B. Método del Identificador EMM (afw#...)</b>", h2_style))
    story.append(Paragraph("1. Avanza por el asistente normal de Android de fábrica hasta la pantalla de inicio de sesión de cuenta de Google.", bullet_style))
    story.append(Paragraph("2. En el correo de Gmail, escribe <b>afw#locksuite</b> (o tu identificador registrado cuando subas la app a Google Play Console).", bullet_style))
    story.append(Paragraph("3. Android reconocerá el tag especial, omitirá la cuenta personal y descargará e instalará directamente LockSuite como administrador.", bullet_style))

    story.append(Paragraph("<b>C. Método NFC (Back-to-Back)</b>", h2_style))
    story.append(Paragraph("1. En un celular de configuración (Staging phone), instala una aplicación de aprovisionamiento (ej: 'NFC Provisioning Tool').", bullet_style))
    story.append(Paragraph("2. Carga en la app los parámetros de LockSuite (el link de la APK y el nombre del receiver de administración).", bullet_style))
    story.append(Paragraph("3. Junta la parte trasera del celular de configuración con el celular destino (en la pantalla inicial de bienvenida).", bullet_style))
    story.append(Paragraph("4. Al hacer el 'beam' inalámbrico, el celular destino descargará y configurará automáticamente LockSuite.", bullet_style))

    story.append(Spacer(1, 5))

    # Knox and ZTE custom DPC configuration parameters
    story.append(Paragraph("3. Parámetros Exactos para las consolas web de KME / ZTE", h1_style))
    story.append(Paragraph("Cuando configures tu perfil de inscripción en la consola web de **Samsung Knox Mobile Enrollment** o de **Google Zero-Touch**, debes crear un perfil de tipo **'Custom DPC'** (o 'Otro DPC') y rellenar estos dos valores clave:", body_style))
    
    story.append(Paragraph(
        "<b>DPC APK Download URL (Enlace del instalador):</b><br/>"
        "https://locksuite-nueva.web.app/locksuite-latest.apk<br/><br/>"
        "<b>DPC Receiver / Component Name (Receptor del administrador):</b><br/>"
        "com.ejemplo.locksuite/com.ejemplo.locksuite.receiver.DeviceAdminReceiver", 
        code_style
    ))
    
    story.append(Paragraph("Al guardar esta configuración en tu consola web y asignársela a tus dispositivos, LockSuite se instalará e iniciará el bloqueo inmediatamente de forma autónoma en cuanto el celular se encienda y se conecte a internet.", body_style))

    # Build the document
    doc.build(story)
    print("PDF generated successfully.")

if __name__ == '__main__':
    generate_pdf()
