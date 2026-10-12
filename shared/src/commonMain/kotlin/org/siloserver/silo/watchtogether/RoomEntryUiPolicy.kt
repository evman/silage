package org.siloserver.silo.watchtogether

/**
 * A create/join operation installs a process-scoped room binding on success.
 * Keep its observer visible until that mutation resolves.
 */
fun canDismissRoomEntry(isBusy: Boolean): Boolean = !isBusy
