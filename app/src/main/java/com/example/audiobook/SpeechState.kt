package com.example.audiobook

/**
 * Singleton to hold speech state across activity and service.
 */
object SpeechState {
    var chunks: List<String> = emptyList()
    var currentIndex: Int = 0
    var isPlaying: Boolean = false
}