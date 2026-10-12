package org.siloserver.silo.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Top-level shared Koin module.
 * After all agents merge, this assembles: networkModule + repositoryModule.
 * Agents 2 and 3 define their modules in NetworkModule.kt and RepositoryModule.kt.
 */
val sharedModule = module {
    // Placeholder -- will include networkModule and repositoryModule after merge
}

/**
 * Returns all shared modules for Koin initialization.
 * Called from platform-specific app startup.
 */
fun sharedModules(): List<Module> = listOf(
    sharedModule,
    networkModule,
    repositoryModule,
)
