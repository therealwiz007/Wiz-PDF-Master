package com.wizlabs.pdfmaster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test fun ordersCornersRegardlessOfInputOrder() {
        val q = Quad.ordered(listOf(P(0.9f, 0.8f), P(0.1f, 0.1f), P(0.1f, 0.9f), P(0.8f, 0.15f)))
        assertEquals(P(0.1f, 0.1f), q.tl)
        assertEquals(P(0.8f, 0.15f), q.tr)
        assertEquals(P(0.9f, 0.8f), q.br)
        assertEquals(P(0.1f, 0.9f), q.bl)
    }

    @Test fun convexity() {
        assertTrue(Quad.FULL.isConvex())
        val bowtie = Quad(P(0f, 0f), P(1f, 1f), P(1f, 0f), P(0f, 1f))
        assertFalse(bowtie.isConvex())
    }

    @Test fun areaOfFullFrameIsOne() = assertEquals(1f, Quad.FULL.area(), 1e-5f)

    @Test fun rotatingFourTimesIsIdentity() {
        val q = Quad(P(.1f, .2f), P(.9f, .1f), P(.8f, .9f), P(.2f, .85f))
        val r = q.rotatedCw().rotatedCw().rotatedCw().rotatedCw()
        assertTrue(q.distanceTo(r) < 1e-5f)
    }

    @Test fun warpSizeUsesLongerEdges() {
        val (w, h) = warpSize(Quad.FULL, 400, 300)
        assertEquals(400, w); assertEquals(300, h)
    }

    @Test fun fitWithinKeepsAspect() {
        assertEquals(3200 to 2400, fitWithin(4000, 3000, 3200))
        assertEquals(100 to 50, fitWithin(100, 50, 3200))
    }

    @Test fun findsAllCaseInsensitive() {
        assertEquals(listOf(0, 10), findAll("Invoice # invoice", "INVOICE"))
        assertEquals(listOf(0, 10), findAll("Invoice # invoice", "invoice"))
        assertTrue(findAll("abc", " ").isEmpty())
    }

    @Test fun pdfSafeDropsUnencodable() {
        assertEquals("Total Rs500 - paid", pdfSafe("Total ₹500 — paid"))
        assertEquals("abc", pdfSafe("aकb中c"))
    }

    @Test fun safeFileNames() {
        assertEquals("a_b_c", safeFileName("a/b:c"))
        assertEquals("Document", safeFileName("   "))
    }

    @Test fun humanSizes() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.5 MB", humanSize(1_572_864))
    }

    @Test fun insetShrinksTowardsCentre() {
        val q = Quad.FULL.inset(0.1f)
        assertEquals(P(0.05f, 0.05f), q.tl)
        assertTrue(q.area() < 1f)
    }
}
