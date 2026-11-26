package com.danya.mafia.server

/**
 * MP3-файлы, которые ведущий добавляет с телефона.
 * Живут в памяти комнаты и раздаются игрокам по адресу /media/<id>.
 */
class MediaLibrary {

    data class Info(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long
    )

    class Track(val info: Info, val bytes: ByteArray)

    private val tracks = LinkedHashMap<String, Track>()
    private var counter = 0

    @Synchronized
    fun add(name: String, mime: String, bytes: ByteArray): Info {
        counter += 1
        val info = Info(
            id = "s$counter",
            name = name.ifBlank { "sound-$counter.mp3" },
            mime = mime,
            size = bytes.size.toLong()
        )
        tracks[info.id] = Track(info, bytes)
        return info
    }

    @Synchronized
    fun get(id: String): Track? = tracks[id]

    @Synchronized
    fun remove(id: String) {
        tracks.remove(id)
    }

    @Synchronized
    fun list(): List<Info> = tracks.values.map { it.info }

    @Synchronized
    fun clear() {
        tracks.clear()
        counter = 0
    }
}
