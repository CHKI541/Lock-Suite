# Reglas de R8 para :admin-app
#
# Esta app es una cáscara de WebView: no tiene reflexión propia, no expone ningún
# @JavascriptInterface, no serializa nada y no usa Firebase. AppCompat, Material y
# SwipeRefreshLayout traen sus propias reglas de consumidor dentro del .aar, así que
# no hay que repetirlas acá.
#
# Lo único que hace falta es poder leer un stack trace si el dueño reporta un cierre
# inesperado desde el celular: sin estas dos líneas, R8 borra los números de línea y
# el reporte queda ilegible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Los WebViewClient/WebChromeClient se instancian desde el propio código (no por
# nombre), así que R8 los resuelve solo. Si alguna vez se agrega un
# @JavascriptInterface, ACÁ hay que agregar el -keep correspondiente o el método
# desaparece en release y la página deja de poder llamarlo:
#
#   -keepclassmembers class com.ejemplo.locksuite.admin.** {
#       @android.webkit.JavascriptInterface <methods>;
#   }
