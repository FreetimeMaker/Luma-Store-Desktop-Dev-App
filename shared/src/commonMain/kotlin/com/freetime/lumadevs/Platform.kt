package com.freetime.lumadevs

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform