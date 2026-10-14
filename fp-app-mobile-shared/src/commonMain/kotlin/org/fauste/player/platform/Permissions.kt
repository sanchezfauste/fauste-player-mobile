package org.fauste.player.platform

/**
 * The manifest permission identifier this OS build requires to read audio
 * files from shared storage: `READ_MEDIA_AUDIO` on API 33+,
 * `READ_EXTERNAL_STORAGE` below. This phase only exposes the constant; the
 * runtime-request UI flow belongs to Phase 4.
 */
expect val audioReadPermission: String
