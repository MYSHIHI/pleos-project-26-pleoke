package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private fun duet() = Session().apply {
        participate(SeatId.A); participate(SeatId.B); enqueue(Catalog.songs.first(), SeatId.A)
    }
    @Test fun soloAssignsEveryPartToPresentSeat() {
        val s = Session(); s.participate(SeatId.B); s.enqueue(Catalog.songs[1], SeatId.B)
        assertTrue(s.current!!.singers.all { it == Singer.B }); assertTrue(s.confirm()); assertTrue(s.ready)
    }
    @Test fun swappingChangesLyricOwnerAndRequiresConfirmation() {
        val s = duet(); s.confirm(); val at = s.current!!.song.beatMs(8)
        assertEquals(Singer.A, s.singerAt(at)); s.cyclePart(0)
        assertEquals(Singer.B, s.singerAt(at)); assertFalse(s.ready)
        s.viewSeat = SeatId.B; assertTrue(s.isMine(s.singerAt(at))); assertTrue(s.confirm())
    }
    @Test fun chorusBelongsToBothSeats() {
        val s = duet(); val at = s.current!!.song.beatMs(40)
        assertEquals(Singer.ALL, s.singerAt(at))
        SeatId.entries.forEach { s.viewSeat = it; assertTrue(s.isMine(s.singerAt(at))) }
    }
    @Test fun fourSeatRecommendationIncludesRearSeatsAndAllChoruses() {
        val s = Session(); SeatId.entries.forEach(s::participate)
        s.enqueue(Catalog.songs.first(), SeatId.C)
        assertEquals(listOf(Singer.A, Singer.B, Singer.ALL, Singer.C, Singer.D, Singer.ALL), s.current!!.singers)
        assertTrue(s.confirm())
        assertEquals(s.encode(), Session.decode(s.encode()).encode())
    }
    @Test fun rearSeatCanClaimPartButMustParticipateAndReconfirm() {
        val s = duet(); assertFalse(s.claimPart(0, SeatId.C))
        s.participate(SeatId.C); s.confirm()
        assertTrue(s.claimPart(0, SeatId.C)); assertFalse(s.ready)
        assertFalse(s.claimPart(-1, SeatId.C)); assertFalse(s.claimPart(99, SeatId.C))
        assertEquals(Singer.C, s.singerAt(5000)); assertTrue(s.confirm())
    }
    @Test fun subscriptionSongDoesNotInventLyricsOrPartTimingAndRoundTrips() {
        val s = duet(); s.next()
        val song = Song.subscription("example.vehicle.music", "차량 음악", "track-5", "서비스 곡", "가수")
        assertTrue(s.enqueue(song, SeatId.B)); assertTrue(s.current!!.confirmed)
        assertTrue(s.current!!.song.parts.isEmpty()); assertTrue(s.current!!.song.lyrics.isEmpty())
        assertEquals(0, s.current!!.song.durationMs); s.positionMs = 91000
        val restored = Session.decode(s.encode()); assertEquals(s.encode(), restored.encode()); assertEquals(91000, restored.positionMs)
        assertEquals("example.vehicle.music", restored.current!!.song.app)
    }
    @Test fun seatCapabilitiesAreScopedAndRotated() {
        val access = SeatAccess(); val original = access.token(SeatId.C)
        assertTrue(access.accepts(SeatId.C, original)); assertFalse(access.accepts(SeatId.D, original))
        assertFalse(access.accepts(SeatId.C, null)); assertFalse(access.accepts(SeatId.C, ""))
        access.renew(); assertFalse(access.accepts(SeatId.C, original)); assertNotEquals(original, access.token(SeatId.C))
        assertTrue(access.url("192.168.0.7", 8792, SeatId.C).contains("seat=C&token="))
    }
    @Test fun leavingReassignsQueuedSongsAndInvalidatesAgreement() {
        val s = duet(); s.enqueue(Catalog.songs[1], SeatId.B); s.confirm(); s.positionMs = 12_345
        s.participate(SeatId.A)
        assertTrue(s.queue.all { !it.confirmed && it.singers.all { p -> p == Singer.B } })
        assertEquals(12_345, s.positionMs); assertFalse(s.ready)
    }
    @Test fun noParticipantsCannotReserveOrConfirm() {
        val s = Session(); assertFalse(s.enqueue(Catalog.songs.first(), SeatId.A)); assertFalse(s.confirm())
        val d = duet(); d.participate(SeatId.A); d.participate(SeatId.B); assertFalse(d.confirm()); assertFalse(d.ready)
        assertEquals(1, Session.decode(d.encode()).queue.size)
    }
    @Test fun roundTripRetainsSongPositionQueueAndPartExchange() {
        val s = duet(); s.cyclePart(0); s.confirm(); s.enqueue(Catalog.songs[2], SeatId.B)
        s.positionMs = 28_321; s.volume = 48; s.viewSeat = SeatId.B
        val restored = Session.decode(s.encode())
        assertEquals(s.encode(), restored.encode()); assertEquals(Singer.B, restored.current!!.singers[0])
    }
    @Test fun corruptOrUnknownStorageIsFreshSession() {
        assertNull(Session.decode("not json").current)
        assertNull(Session.decode(duet().encode().replace("campfire", "missing")).current)
        assertNull(Session.decode(duet().encode().replace("\"version\":1", "\"version\":99")).current)
    }
    @Test fun restoredPositionAndVolumeAreBounded() {
        val s = duet(); s.positionMs = Int.MAX_VALUE; s.volume = -500
        val r = Session.decode(s.encode()); assertEquals(r.current!!.song.durationMs, r.positionMs); assertEquals(0, r.volume)
    }
    @Test fun completionDoesNotMakeNextSongReady() {
        val s = duet(); s.confirm(); s.enqueue(Catalog.songs[1], SeatId.B); s.finished = true
        assertFalse(s.ready); s.next(); assertEquals("starlight", s.current!!.songId)
        assertEquals(0, s.positionMs); assertFalse(s.finished); assertFalse(s.ready)
    }
    @Test fun queueIsBoundedAndNonparticipantsCannotReserve() {
        val s = duet(); repeat(11) { assertTrue(s.enqueue(Catalog.songs.first(), SeatId.B)) }
        assertFalse(s.enqueue(Catalog.songs[1], SeatId.A)); assertEquals(12, Session.decode(s.encode()).queue.size)
    }
    @Test fun timecodesCoverAllLyricsAndExactPartBoundaries() {
        Catalog.songs.forEach { song ->
            assertEquals(22, song.lyrics.size); assertEquals(-1, song.partAt(0))
            song.parts.forEachIndexed { i, part ->
                assertEquals(i, song.partAt(song.beatMs(part.startBeat)))
                assertEquals(i, song.partAt(song.beatMs(part.endBeat) - 1))
            }
            assertEquals(-1, song.partAt(song.durationMs)); assertEquals(0, song.lyricAt(song.beatMs(8)))
            assertTrue(song.lyrics.last().beat < 96)
        }
    }
    @Test fun safetyRequiresRealParkAndUnrestrictedUxWithNoInterruption() {
        assertTrue(ParkState("P", true).allowed)
        assertFalse(ParkState().allowed); assertFalse(ParkState(null, true).allowed)
        listOf("D", "N", "R", "?").forEach { assertFalse(ParkState(it, true).allowed) }
        assertFalse(ParkState("P", false).allowed); assertFalse(ParkState("P", true, true).allowed)
    }
}
