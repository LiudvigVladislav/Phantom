// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Internal helpers ──────────────────────────────────────────────

private fun parsePath(svgPath: String, s: Float) =
    PathParser().parsePathString(svgPath).toPath().also {
        it.transform(Matrix().apply { scale(s, s) })
    }

@Composable
private fun PhStrokePath(
    svgPath: String,
    color: Color,
    modifier: Modifier,
    svgStrokeWidth: Float = 1.6f,
) {
    Canvas(modifier = modifier) {
        val s = size.width / 24f
        drawPath(
            parsePath(svgPath, s), color = color,
            style = Stroke(svgStrokeWidth * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ── Navigation & top-bar ──────────────────────────────────────────

// back chevron ← M15 5l-7 7 7 7
@Composable
fun PhIconBack(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    PhStrokePath("M15 5l-7 7 7 7", color, modifier.then(Modifier.size(size)), 1.7f)
}

// right chevron › M9 5l7 7-7 7
@Composable
fun PhIconChevron(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath("M9 5l7 7-7 7", color, modifier.then(Modifier.size(size)))
}

// pencil-compose — body + tip
@Composable
fun PhIconPencilCompose(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M4 20h4l11-11-4-4L4 16v4z", s), color, style = st)
        drawPath(parsePath("M15 5l4 4", s), color, style = st)
    }
}

// Lucide Edit3 — pencil with the canonical baseline tick under the
// eraser so it reads as "edit" rather than a generic pencil.
@Composable
fun PhIconEdit(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M12 20h9M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z",
        color, modifier.then(Modifier.size(size)),
    )
}

// ── Messaging ────────────────────────────────────────────────────

// chat bubble M4 5h16v11H9l-5 4V5z
@Composable
fun PhIconMessage(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    PhStrokePath("M4 5h16v11H9l-5 4V5z", color, modifier.then(Modifier.size(size)))
}

// paperclip — arcs expanded
@Composable
fun PhIconPaperclip(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    PhStrokePath(
        "M20 10l-9 9a5 5 0 0 1 -7 -7l9-9a3.5 3.5 0 1 1 5 5l-9 9a2 2 0 0 1 -3 -3l8-8",
        color, modifier.then(Modifier.size(size)),
    )
}

// microphone — rect body + arc + stem, arc expanded
@Composable
fun PhIconMic(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(9f * s, 3f * s), Size(6f * s, 12f * s), CornerRadius(3f * s), st)
        drawPath(parsePath("M5 11a7 7 0 0 0 14 0M12 18v3", s), color, style = st)
    }
}

// mic crossed out — mic outline + diagonal line
@Composable
fun PhIconMicOff(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(9f * s, 3f * s), Size(6f * s, 12f * s), CornerRadius(3f * s), st)
        drawPath(parsePath("M5 11a7 7 0 0 0 14 0M12 18v3", s), color, style = st)
        drawLine(color, Offset(3f * s, 3f * s), Offset(21f * s, 21f * s), 1.8f * s, StrokeCap.Round)
    }
}

// send arrow up
@Composable
fun PhIconArrowUp(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    PhStrokePath("M12 19V5M12 5l-6 6M12 5l6 6", color, modifier.then(Modifier.size(size)), 2.2f)
}

// reply arrow
@Composable
fun PhIconReply(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M9 14L3 8l6-6M3 8h9a8 8 0 0 1 8 8v6",
        color, modifier.then(Modifier.size(size)),
    )
}

// forward arrow
@Composable
fun PhIconForward(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M15 14l6-6-6-6M21 8h-9a8 8 0 0 0 -8 8v6",
        color, modifier.then(Modifier.size(size)),
    )
}

// download arrow
@Composable
fun PhIconDownload(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    PhStrokePath("M12 4v12M12 16l-4-4M12 16l4-4M4 20h16", color, modifier.then(Modifier.size(size)), 1.8f)
}

// share (upload-like arrow + bottom rect)
@Composable
fun PhIconShare(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath(
        "M12 3v12M12 3l-4 4M12 3l4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2 -2v-7",
        color, modifier.then(Modifier.size(size)), 1.8f,
    )
}

// copy — two overlapping rectangles
@Composable
fun PhIconCopy(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(8f * s, 8f * s), Size(12f * s, 12f * s), CornerRadius(2f * s), st)
        drawPath(
            parsePath("M16 8V5a1 1 0 0 0 -1 -1H5a1 1 0 0 0 -1 1v10a1 1 0 0 0 1 1h3", s),
            color, style = st,
        )
    }
}

// Lucide Trash2 — trash bin with two inner vertical guide lines so it
// reads as "delete content" rather than just an empty container.
@Composable
fun PhIconTrash(color: Color, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    PhStrokePath(
        "M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6",
        color, modifier.then(Modifier.size(size)),
    )
}

// pin (filled, small)
@Composable
fun PhIconPin(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        drawPath(parsePath("M16 3l5 5-5 1-3 6-3-3-6 6 6-6-3-3 6-3 1-5z", s), color)
    }
}

// Lucide Pin — front-view pushpin with the canonical 4.76 head ratio
// and a 5dp tail. Replaces the earlier hand-rolled glyph that read
// more like a vertical needle than a pin.
@Composable
fun PhIconPinAction(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M12 17v5M9 10.76V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v4.76l3 4.24v2H6v-2l3-4.24z",
        color, modifier.then(Modifier.size(size)),
    )
}

// bookmark
@Composable
fun PhIconBookmark(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    PhStrokePath("M6 4h12v17l-6-4-6 4V4z", color, modifier.then(Modifier.size(size)))
}

// note with edit lines
@Composable
fun PhIconNoteEdit(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath("M4 4h10M4 10h10M4 16h6M16 14l5-5-3-3-5 5v3h3z", color, modifier.then(Modifier.size(size)))
}

// ── Status / checks ───────────────────────────────────────────────

// single checkmark ✓
@Composable
fun PhIconCheck(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath("M4 12l5 5 11-12", color, modifier.then(Modifier.size(size)), 1.8f)
}

// double checkmark ✓✓
@Composable
fun PhIconDoubleCheck(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M2 13l4 4 9-10", s), color, style = st)
        drawPath(parsePath("M9 13l4 4 9-10", s), color, style = st)
    }
}

// checkbox with checkmark
@Composable
fun PhIconCheck3(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        drawRoundRect(color, Offset(3f * s, 3f * s), Size(18f * s, 18f * s), CornerRadius(3f * s),
            Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(parsePath("M8 12l3 3 5-6", s), color,
            style = Stroke(1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── People ────────────────────────────────────────────────────────

// person silhouette — circle head + arc body
@Composable
fun PhIconPerson(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 3.5f * s, Offset(12f * s, 8f * s), style = st)
        drawPath(parsePath("M4 20a8 8 0 0 1 16 0", s), color, style = st)
    }
}

// two persons (groups)
@Composable
fun PhIconUsers(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 3f * s, Offset(9f * s, 9f * s), style = st)
        drawPath(parsePath("M3 19a6 6 0 0 1 12 0", s), color, style = st)
        drawCircle(color, 2.5f * s, Offset(17f * s, 8f * s), style = st)
        drawPath(parsePath("M15 19a5 5 0 0 1 6 -4", s), color, style = st)
    }
}

// megaphone (channels)
@Composable
fun PhIconMegaphone(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M3 9v6l8 3V6l-8 3z", s), color, style = st)
        drawPath(parsePath("M11 6l10-3v18l-10-3", s), color, style = st)
    }
}

// ── Security ──────────────────────────────────────────────────────

// shield — arcs expanded
@Composable
fun PhIconShield(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M12 3l8 3v6a9 9 0 0 1 -8 9 9 9 0 0 1 -8 -9V6l8-3z",
        color, modifier.then(Modifier.size(size)),
    )
}

// shield + checkmark — arcs expanded
@Composable
fun PhIconShieldCheck(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        drawPath(parsePath("M12 3l8 3v6a9 9 0 0 1 -8 9 9 9 0 0 1 -8 -9V6l8-3z", s), color,
            style = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(parsePath("M8.5 12l2.5 2.5L16 9.5", s), color,
            style = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// lock — rect body + shackle arc expanded
@Composable
fun PhIconLock(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(5f * s, 11f * s), Size(14f * s, 10f * s), CornerRadius(2f * s), st)
        drawPath(parsePath("M8 11V8a4 4 0 1 1 8 0v3", s), color, style = st)
    }
}

// eye — smooth curves + pupil circle
@Composable
fun PhIconEye(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z", s), color, style = st)
        drawCircle(color, 3f * s, Offset(12f * s, 12f * s), style = st)
    }
}

// timer / clock
@Composable
fun PhIconTimer(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 8f * s, Offset(12f * s, 13f * s), style = st)
        drawPath(parsePath("M12 9v4l2.5 2M9 3h6M12 3V1", s), color, style = st)
    }
}

// ── Calls & phone ────────────────────────────────────────────────

// phone handset (stroked) — arcs expanded
@Composable
fun PhIconPhone(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    PhStrokePath(
        "M5 4h3l2 5-2 1.5a11 11 0 0 0 5.5 5.5L15 14l5 2v3a2 2 0 0 1 -2 2A15 15 0 0 1 3 6a2 2 0 0 1 2 -2z",
        color, modifier.then(Modifier.size(size)),
    )
}

// phone handset (filled) — for answer call button
@Composable
fun PhIconPhoneFill(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        drawPath(
            parsePath("M5 4h3l2 5-2 1.5a11 11 0 0 0 5.5 5.5L15 14l5 2v3a2 2 0 0 1 -2 2A15 15 0 0 1 3 6a2 2 0 0 1 2 -2z", s),
            color,
        )
    }
}

// end call — filled phone rotated 135°
@Composable
fun PhIconCallEnd(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        rotate(135f, Offset(cx, cy)) {
            drawPath(
                parsePath("M5 4h3l2 5-2 1.5a11 11 0 0 0 5.5 5.5L15 14l5 2v3a2 2 0 0 1 -2 2A15 15 0 0 1 3 6a2 2 0 0 1 2 -2z", s),
                color,
            )
        }
    }
}

// incoming call arrow
@Composable
fun PhIconArrowIn(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath("M19 5L5 19M5 19h9M5 19v-9", color, modifier.then(Modifier.size(size)), 1.8f)
}

// outgoing call arrow
@Composable
fun PhIconArrowOut(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath("M5 19L19 5M19 5h-9M19 5v9", color, modifier.then(Modifier.size(size)), 1.8f)
}

// ── Media ─────────────────────────────────────────────────────────

// video camera
@Composable
fun PhIconVideo(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(2f * s, 6f * s), Size(14f * s, 12f * s), CornerRadius(2f * s), st)
        drawPath(parsePath("M16 10l6-3v10l-6-3z", s), color, style = st)
    }
}

// play triangle (filled)
@Composable
fun PhIconPlay(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        drawPath(parsePath("M6 4l14 8-14 8V4z", s), color)
    }
}

// pause — two bars (filled)
@Composable
fun PhIconPause(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val r = CornerRadius(1.5f * s)
        drawRoundRect(color, Offset(5f * s, 4f * s), Size(4f * s, 16f * s), r)
        drawRoundRect(color, Offset(15f * s, 4f * s), Size(4f * s, 16f * s), r)
    }
}

// speaker volume on — simple speaker + wave
@Composable
fun PhIconVolume(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M3 9v6h4l5 5V4L7 9H3z", s), color, style = st)
        drawPath(parsePath("M16.5 7.5a5 5 0 0 1 0 9M19 5a9 9 0 0 1 0 14", s), color, style = st)
    }
}

// speaker volume off — speaker + X
@Composable
fun PhIconVolumeOff(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M3 9v6h4l5 5V4L7 9H3z", s), color, style = st)
        drawLine(color, Offset(17f * s, 9f * s), Offset(23f * s, 15f * s), 1.6f * s, StrokeCap.Round)
        drawLine(color, Offset(23f * s, 9f * s), Offset(17f * s, 15f * s), 1.6f * s, StrokeCap.Round)
    }
}

// ── Settings & UI ────────────────────────────────────────────────

// gear — circle + gear outline, all arcs expanded
@Composable
fun PhIconGear(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        drawCircle(color, 3f * s, Offset(cx, cy),
            style = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(
            parsePath(
                "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1 -2.83 2.83l-.06-.06" +
                "a1.65 1.65 0 0 0 -1.82 -.33 1.65 1.65 0 0 0 -1 1.51V21a2 2 0 1 1 -4 0v-.09" +
                "a1.65 1.65 0 0 0 -1 -1.51 1.65 1.65 0 0 0 -1.82 .33l-.06.06a2 2 0 0 1 -2.83 -2.83" +
                "l.06-.06a1.65 1.65 0 0 0 .33 -1.82 1.65 1.65 0 0 0 -1.51 -1H3a2 2 0 1 1 0 -4" +
                "h.09a1.65 1.65 0 0 0 1.51 -1 1.65 1.65 0 0 0 -.33 -1.82l-.06-.06a2 2 0 1 1 2.83 -2.83" +
                "l.06.06a1.65 1.65 0 0 0 1.82 .33H9a1.65 1.65 0 0 0 1 -1.51V3a2 2 0 1 1 4 0v.09" +
                "a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82 -.33l.06-.06a2 2 0 1 1 2.83 2.83" +
                "l-.06.06a1.65 1.65 0 0 0 -.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4" +
                "h-.09a1.65 1.65 0 0 0 -1.51 1z", s,
            ), color,
            style = Stroke(1.3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// bell — arc expanded
@Composable
fun PhIconBell(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath("M6 9a6 6 0 0 1 12 0v4l2 3H4l2-3V9z", s), color, style = st)
        drawPath(parsePath("M10 19a2 2 0 0 0 4 0", s), color, style = st)
    }
}

// search — circle + line
@Composable
fun PhIconSearch(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round)
        drawCircle(color, 7f * s, Offset(11f * s, 11f * s), style = st)
        drawLine(color, Offset(16.5f * s, 16.5f * s), Offset(21f * s, 21f * s), 1.6f * s, StrokeCap.Round)
    }
}

// funnel / filter
@Composable
fun PhIconFunnel(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath("M3 4h18l-7 9v6l-4 2v-8L3 4z", color, modifier.then(Modifier.size(size)))
}

// globe — circle + meridians, arcs expanded
@Composable
fun PhIconGlobe(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 9f * s, Offset(12f * s, 12f * s), style = st)
        drawPath(parsePath("M3 12h18M12 3a13 13 0 0 1 0 18M12 3a13 13 0 0 0 0 18", s), color, style = st)
    }
}

// sun — circle + 8 rays
@Composable
fun PhIconSun(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 4f * s, Offset(12f * s, 12f * s), style = st)
        drawPath(parsePath("M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4", s), color, style = st)
    }
}

// device (phone outline) — rounded rect + home circle
@Composable
fun PhIconDevice(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        drawRoundRect(color, Offset(7f * s, 3f * s), Size(10f * s, 18f * s), CornerRadius(2f * s),
            Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 1f * s, Offset(12f * s, 18f * s))
    }
}

// three-dot vertical menu (filled dots)
@Composable
fun PhIconMoreVert(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val r = 1.8f * s; val cx = 12f * s
        drawCircle(color, r, Offset(cx, 5f * s))
        drawCircle(color, r, Offset(cx, 12f * s))
        drawCircle(color, r, Offset(cx, 19f * s))
    }
}

// three-dot horizontal menu (filled dots) — chat-header overflow per
// FULL_COMPOSE §05 ActiveChatScreen mock.
@Composable
fun PhIconMoreHoriz(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val r = 1.8f * s; val cy = 12f * s
        drawCircle(color, r, Offset(5f * s, cy))
        drawCircle(color, r, Offset(12f * s, cy))
        drawCircle(color, r, Offset(19f * s, cy))
    }
}

// radar — concentric rings + sweep line + center dot.
// Used for the Nearby (mesh) bottom-nav tab. Architectural truth, not a wifi
// glyph: PHANTOM finds peers via BLE/WiFi-Direct, not internet APs.
@Composable
fun PhIconRadar(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val cx = 12f * s
        val cy = 12f * s
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Outer + middle rings
        drawCircle(color, 9f * s, Offset(cx, cy), style = st)
        drawCircle(color, 5.5f * s, Offset(cx, cy), style = st)
        // Sweep arm pointing up-right
        drawPath(
            parsePath("M12 12L20 5", s),
            color,
            style = st,
        )
        // Center dot
        drawCircle(color, 1.5f * s, Offset(cx, cy))
    }
}

// ── Additional icons — FULL_COMPOSE §02B catalogue (cat 02 / 03 / 06 / 12 / 13)
// All Lucide-aligned at stroke 1.6, cap/join Round, on a 24×24 viewBox.

// alert-circle — circle + bang
@Composable
fun PhIconAlertCircle(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 9f * s, Offset(12f * s, 12f * s), style = st)
        drawPath(parsePath("M12 8v4M12 16h.01", s), color, style = st)
    }
}

// eye-off — eye with diagonal slash
@Composable
fun PhIconEyeOff(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M3 3l18 18M9.88 5.12A11.85 11.85 0 0112 5c5 0 9.27 3.11 11 7-.6 1.34-1.46 2.55-2.51 3.55M6.61 6.61C4.61 8 3.18 9.84 2.46 12c1.73 3.89 6 7 11 7 1.37 0 2.69-.23 3.93-.65M9.88 9.88a3 3 0 104.24 4.24",
        color, modifier.then(Modifier.size(size)),
    )
}

// qr-code — three corner brackets + center pixel cluster
@Composable
fun PhIconQrCode(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Three corner squares
        drawPath(parsePath("M3 3h6v6H3zM15 3h6v6h-6zM3 15h6v6H3z", s), color, style = st)
        // Inner solid centers
        drawPath(parsePath("M5 5h2v2H5zM17 5h2v2h-2zM5 17h2v2H5z", s), color)
        // Bottom-right cluster
        drawPath(parsePath("M13 13h3v3h-3zM18 13h3M13 18v3M18 18h3v3h-3z", s), color, style = st)
    }
}

// plus
@Composable
fun PhIconPlus(color: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    PhStrokePath("M12 5v14M5 12h14", color, modifier.then(Modifier.size(size)))
}

// x — close
@Composable
fun PhIconX(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath("M18 6L6 18M6 6l12 12", color, modifier.then(Modifier.size(size)))
}

// chevron-left
@Composable
fun PhIconChevronLeft(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath("M15 18l-6-6 6-6", color, modifier.then(Modifier.size(size)))
}

// chevron-down
@Composable
fun PhIconChevronDown(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    PhStrokePath("M6 9l6 6 6-6", color, modifier.then(Modifier.size(size)))
}

// star — outline
@Composable
fun PhIconStar(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
        color, modifier.then(Modifier.size(size)),
    )
}

// flag — moderation report
@Composable
fun PhIconFlag(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1zM4 22V15",
        color, modifier.then(Modifier.size(size)),
    )
}

// ban — block, prohibition
@Composable
fun PhIconBan(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 9f * s, Offset(12f * s, 12f * s), style = st)
        drawPath(parsePath("M5.64 5.64l12.72 12.72", s), color, style = st)
    }
}

// smile-plus — emoji reaction trigger
@Composable
fun PhIconSmile(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Round smile face: outer circle + smile arc + two eye dots.
        drawPath(
            parsePath(
                "M21 12c0 5-4 9-9 9s-9-4-9-9 4-9 9-9 9 4 9 9M8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01",
                s,
            ),
            color, style = st,
        )
    }
}

@Composable
fun PhIconSmilePlus(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Smile arc + eyes
        drawPath(
            parsePath("M21 12c0 5-4 9-9 9s-9-4-9-9 4-9 9-9M8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01", s),
            color, style = st,
        )
        // Plus
        drawPath(parsePath("M19 3v4M17 5h4", s), color, style = st)
    }
}

// bell-ring — bell with subtle radiating dots
@Composable
fun PhIconBellRing(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M6 8a6 6 0 0112 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 003.4 0M4 2C2.8 3.7 2 5.7 2 8M22 8c0-2.3-.8-4.3-2-6",
        color, modifier.then(Modifier.size(size)),
    )
}

// bell-off — muted bell
@Composable
fun PhIconBellOff(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M8.7 3a6 6 0 019.3 5v2.5M17 17H3s3-2 3-9M3 3l18 18M10.3 21a1.94 1.94 0 003.4 0",
        color, modifier.then(Modifier.size(size)),
    )
}

// ellipsis-vertical (alias for MoreVert; bigger 22dp default for context menu triggers)
@Composable
fun PhIconEllipsisVertical(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) =
    PhIconMoreVert(color, modifier, size)

// pin-off — slashed pin
@Composable
fun PhIconPinOff(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M12 17v5M9 10.76V6a2 2 0 012-2h2a2 2 0 012 2v4.76l-1 .76M3 3l18 18",
        color, modifier.then(Modifier.size(size)),
    )
}

// reply-all — chevron-back-back glyph
@Composable
fun PhIconReplyAll(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    PhStrokePath(
        "M7 17l-5-5 5-5M12 17l-5-5 5-5M22 18v-2a4 4 0 00-4-4H7",
        color, modifier.then(Modifier.size(size)),
    )
}

// message-circle — speech bubble
@Composable
fun PhIconMessageCircle(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M21 11.5a8.38 8.38 0 01-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z",
        color, modifier.then(Modifier.size(size)),
    )
}

// award — premium tier
@Composable
fun PhIconAward(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, 4f * s, Offset(12f * s, 8f * s), style = st)
        drawPath(
            parsePath("M8.21 13.89L7 23l5-3 5 3-1.21-9.12", s),
            color, style = st,
        )
    }
}

// gift — wrapped box with bow
@Composable
fun PhIconGift(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M20 12v10H4V12M2 7h20v5H2zM12 22V7M12 7H7.5a2.5 2.5 0 010-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 000-5C13 2 12 7 12 7z",
        color, modifier.then(Modifier.size(size)),
    )
}

// zap — lightning
@Composable
fun PhIconZap(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M13 2L3 14h9l-1 8 10-12h-9l1-8z",
        color, modifier.then(Modifier.size(size)),
    )
}

// dollar-sign — billing / pricing
@Composable
fun PhIconDollarSign(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M12 1v22M17 5H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6",
        color, modifier.then(Modifier.size(size)),
    )
}

// hash — channel sigil
@Composable
fun PhIconHash(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M4 9h16M4 15h16M10 3L8 21M16 3l-2 18",
        color, modifier.then(Modifier.size(size)),
    )
}

// archive — box with lid
@Composable
fun PhIconArchive(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M21 8v13H3V8M1 3h22v5H1zM10 12h4",
        color, modifier.then(Modifier.size(size)),
    )
}

// ── Settings-screen icons (FULL_COMPOSE §06) ──────────────────────

// key — circle bow + shaft with two notches
@Composable
fun PhIconKey(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // bow — circle at (8, 15) radius 4
        drawPath(parsePath(
            "M12 15a4 4 0 1 1 -8 0 4 4 0 0 1 8 0z", s), color, style = st)
        // shaft from (12, 11) to (21, 2) with two notches
        drawPath(parsePath(
            "M11.5 11.5L21 2M16.5 6.5l3 3M19 4l3 3", s), color, style = st)
    }
}

// credit card — rounded rect + magstripe
@Composable
fun PhIconCreditCard(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(2f * s, 5f * s), Size(20f * s, 14f * s),
            CornerRadius(2f * s), st)
        drawPath(parsePath("M2 10h20", s), color, style = st)
    }
}

// clock — circle + two hands at 12 and 3
@Composable
fun PhIconClock(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M12 22a10 10 0 1 0 0 -20 10 10 0 0 0 0 20zM12 6v6l4 2",
        color, modifier.then(Modifier.size(size)),
    )
}

// camera — body + viewfinder bump + lens circle
@Composable
fun PhIconCamera(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier = modifier.then(Modifier.size(size))) {
        val s = this.size.width / 24f
        val st = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(parsePath(
            "M3 7h3l2-3h8l2 3h3v13H3z", s), color, style = st)
        // lens circle at (12, 13) radius 4
        drawPath(parsePath(
            "M16 13a4 4 0 1 1 -8 0 4 4 0 0 1 8 0z", s), color, style = st)
    }
}

// database — three stacked ellipses (cylinder side view)
@Composable
fun PhIconDatabase(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M4 6c0-1.66 3.58-3 8-3s8 1.34 8 3-3.58 3-8 3-8-1.34-8-3z" +
            "M4 6v6c0 1.66 3.58 3 8 3s8-1.34 8-3V6" +
            "M4 12v6c0 1.66 3.58 3 8 3s8-1.34 8-3v-6",
        color, modifier.then(Modifier.size(size)),
    )
}

// info — circle + i (dot + vertical bar)
@Composable
fun PhIconInfo(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M12 22a10 10 0 1 0 0 -20 10 10 0 0 0 0 20zM12 16v-5M12 8h0.01",
        color, modifier.then(Modifier.size(size)),
    )
}

// file with text — page outline + folded corner + 3 lines of text
@Composable
fun PhIconFileText(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    PhStrokePath(
        "M14 2H6a2 2 0 0 0 -2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2V8z" +
            "M14 2v6h6M9 13h6M9 17h6M9 9h2",
        color, modifier.then(Modifier.size(size)),
    )
}
