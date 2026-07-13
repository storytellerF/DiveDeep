package com.storyteller_f.divedeep

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform