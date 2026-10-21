# The es-ES bundle (Task 5, i18n): la traducción castellana de todos los
# textos visibles de la aplicación, redactada a mano (no máquina) para el
# público español del proyecto. Cada clave refleja su hermana en-US; la
# costura (ui/strings/FluentStrings.kt) cae de vuelta al inglés cuando una
# clave falte aquí.

### Navegación
nav-player = Reproductor
nav-library = Biblioteca
nav-settings = Ajustes

### Pantalla de biblioteca
library-search-label = Buscar
library-search-description = Buscar en la cola
library-import = Importar
library-importing = Importando…
library-imported = Importado: {$path}
library-dismiss = Descartar
library-empty-queue = Importa música para crear la cola
library-empty-search = Ninguna pista coincide con la búsqueda.

### Mensajes que el estado expone a través de los view models
player-busy = El reproductor estaba ocupado y descartó la orden — inténtalo de nuevo.
library-permission-denied = Para importar necesitas acceso a tus archivos de audio — concede el permiso y pulsa Importar de nuevo.
library-import-failed = la importación falló

### Pantalla del reproductor
player-up-next = A continuación: {$title}
unknown-value = —

### Controles de reproducción
control-seek = Barra de progreso
control-previous = Pista anterior
control-play = Reproducir
control-pause = Pausar
control-playing = En reproducción
control-paused = En pausa
control-volume = Volumen
timecode = {$position} / {$duration}

### Pantalla de ajustes
settings-backend-title = Salida de audio
settings-no-backends = No se ha detectado ninguna salida de audio.
settings-remote-title = Control remoto
settings-remote-on = Activado en {$address}
settings-remote-off = Desactivado
settings-refresh = Actualizar
settings-volume-title = Volumen
settings-applying = Aplicando…
backend-availability = {$available ->
    [true] Disponible
   *[false] No disponible
}
backend-row = {$name}, {$available ->
    [true] disponible
   *[false] no disponible
}, {$selected ->
    [true] seleccionado
   *[false] no seleccionado
}

### Fila de la cola (TrackRow)
row-arm = Establecer como pista siguiente
row-now-playing = En reproducción
row-up-next = A CONTINUACIÓN
row-armed = Establecida como pista siguiente

### Notificación (nombre del canal + etiqueta de la acción Detener)
notification-channel = Reproducción
notification-stop = Detener
