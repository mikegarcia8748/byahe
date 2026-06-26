package com.mikedev.ph.byahe

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform