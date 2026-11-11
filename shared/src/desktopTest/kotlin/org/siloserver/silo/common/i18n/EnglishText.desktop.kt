package org.siloserver.silo.common.i18n

/** Desktop ships English only, so the app's own resolver is the English one. */
actual fun UiText.english(): String = text()
