package org.fauste.player.di

import org.fauste.player.viewmodel.LibraryViewModel
import org.fauste.player.viewmodel.PlayerViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * composeApp's Koin contribution: the screens' ViewModels, plus nothing
 * else — the shared module's [appModule] owns the whole state-layer
 * graph these definitions resolve from.
 *
 * Placement judgment (documented): commonMain, not androidMain — Koin's CMP
 * convention is that ViewModels and their modules are multiplatform
 * (`androidx.lifecycle.ViewModel` reaches commonMain through
 * koin-compose-viewmodel's api surface, redirecting to the real androidx
 * class on Android), and neither definition contains a platform bit. The
 * Application class (androidMain) merely starts the container.
 *
 * The definitions use the core `viewModel` DSL with explicit `get`s rather
 * than `viewModelOf(::…)`: the constructors' dispatcher parameters need the
 * shared module's [IoDispatcher] QUALIFIER (the graph holds two
 * CoroutineDispatcher definitions, Io and Default), and the
 * constructor-reference DSL resolves parameters by type alone. The state
 * layer is injected as kotlin `Lazy` handles on purpose — resolving it inside
 * the ViewModel's first dispatcher coroutine keeps the engine's heavy first
 * resolution off the main thread (see PlayerViewModel's KDoc).
 */
val viewModelModule =
    module {
        viewModel { PlayerViewModel(inject(), inject(), inject(), get(IoDispatcher)) }
        viewModel { LibraryViewModel(inject(), inject(), inject(), get(IoDispatcher)) }
    }
