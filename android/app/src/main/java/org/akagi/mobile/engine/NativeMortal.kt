package org.akagi.mobile.engine

/** Native entry points are kept by proguard-rules.pro; all calls use one worker queue. */
internal object NativeMortal {
    init { System.loadLibrary("akagi_mortal") }

    @JvmStatic external fun create(player: Int, players: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun accept(handle: Long, event: String): String
    @JvmStatic external fun snapshot(handle: Long): String
    @JvmStatic external fun observation(handle: Long, kan: Boolean): FloatArray
    @JvmStatic external fun resolve(handle: Long, scores: FloatArray, kanScores: FloatArray): String
    @JvmStatic external fun forkReach(handle: Long): Long
}
