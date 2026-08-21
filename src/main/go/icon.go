// Copyright (c) 2026 The Agency HQ
// SPDX-License-Identifier: MIT

package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"

	"golang.org/x/image/vector"
)

// This file renders the tray icon: a bold pair of curly braces with the status glyph between them — {✓}, {?}, {!},
// and { } when the daemon is not running. Everything is built from stroked arcs, stems, and dots so the icon stays
// crisp at menu bar sizes without any font to rasterize with.

// kappa is the standard cubic Bézier approximation constant for a quarter circle.
const kappa = 0.5522847498

// renderIcon draws the braces plus the state glyph at the given edge length and returns PNG bytes. The shapes are
// filled with the given color on transparent; the alpha channel alone is what a macOS template icon uses.
//
// Every shape gets its own rasterizer pass. Overlapping shapes in a single pass cancel where they overlap — the
// rasterizer sums signed coverage — while separate passes simply union, which is what lets tangent segments join
// smoothly under their round caps.
func renderIcon(state trayState, size int, fill color.Color) []byte {
	s := float64(size)
	dst := image.NewRGBA(image.Rect(0, 0, size, size))
	paint := func(build func(*vector.Rasterizer)) {
		ras := vector.NewRasterizer(size, size)
		build(ras)
		ras.Draw(dst, dst.Bounds(), image.NewUniform(fill), image.Point{})
	}

	brace(paint, s, false)
	brace(paint, s, true)
	glyph(paint, state, s)

	var buf bytes.Buffer
	if err := png.Encode(&buf, dst); err != nil {
		return nil
	}
	return buf.Bytes()
}

// brace paints one curly brace: a hook at each end, two stems, and the pointed jut in the middle, all tangent arcs
// and lines stroked at the same width. Angles are degrees in screen coordinates — y grows downward, so 0 is east of
// an arc's center, 90 south, 180 west, and 270 north.
func brace(paint func(func(*vector.Rasterizer)), s float64, mirrored bool) {
	w := 0.095 * s
	r := 0.10 * s
	top := 0.13 * s
	bottom := 0.87 * s
	mid := 0.50 * s
	spine := 0.18 * s

	if mirrored {
		spine = s - spine
		r = -r
		// A negative radius mirrors every x offset below; the arcs mirror by angle instead
	}
	arcAt := func(cx, cy, from, to float64) {
		if mirrored {
			// Mirror a sector across the vertical axis: θ becomes 180−θ, and the sweep is re-ordered to stay
			// increasing so the round caps bulge outward
			from, to = 180-to, 180-from
			if from < 0 {
				from, to = from+360, to+360
			}
		}
		radius := math.Abs(r)
		paint(func(ras *vector.Rasterizer) { arc(ras, cx, cy, radius, w, from, to) })
	}
	stem := func(y1, y2 float64) {
		paint(func(ras *vector.Rasterizer) { capsule(ras, spine, y1, spine, y2, w) })
	}

	arcAt(spine+r, top+math.Abs(r), 180, 270)   // top hook, tip pointing inward
	stem(top+math.Abs(r), mid-math.Abs(r))      // upper stem
	arcAt(spine-r, mid-math.Abs(r), 0, 90)      // upper half of the middle jut
	arcAt(spine-r, mid+math.Abs(r), 270, 360)   // lower half of the middle jut
	stem(mid+math.Abs(r), bottom-math.Abs(r))   // lower stem
	arcAt(spine+r, bottom-math.Abs(r), 90, 180) // bottom hook, tip pointing inward
}

// glyph adds the status shape between the braces.
func glyph(paint func(func(*vector.Rasterizer)), state trayState, s float64) {
	switch state {
	case stateHealthy:
		w := 0.10 * s
		paint(func(ras *vector.Rasterizer) { capsule(ras, 0.33*s, 0.53*s, 0.45*s, 0.65*s, w) })
		paint(func(ras *vector.Rasterizer) { capsule(ras, 0.45*s, 0.65*s, 0.68*s, 0.37*s, w) })
	case stateLoggedOut:
		w := 0.10 * s
		paint(func(ras *vector.Rasterizer) { arc(ras, 0.50*s, 0.38*s, 0.13*s, w, 180, 450) })
		paint(func(ras *vector.Rasterizer) { capsule(ras, 0.50*s, 0.51*s, 0.50*s, 0.55*s, w) })
		paint(func(ras *vector.Rasterizer) { circle(ras, 0.50*s, 0.72*s, 0.058*s) })
	case stateUnreachable:
		paint(func(ras *vector.Rasterizer) { capsule(ras, 0.50*s, 0.30*s, 0.50*s, 0.52*s, 0.11*s) })
		paint(func(ras *vector.Rasterizer) { circle(ras, 0.50*s, 0.72*s, 0.058*s) })
	case stateDisconnected:
		// Braces alone: the daemon is not running, so there is no status to show between them
	}
}

// capsule fills a stroked line segment with round caps as one closed outline: a side, a semicircle around the end,
// the other side back, and a semicircle around the start. One outline means no overlapping subpaths to cancel.
func capsule(ras *vector.Rasterizer, x1, y1, x2, y2, w float64) {
	length := math.Hypot(x2-x1, y2-y1)
	if length == 0 {
		circle(ras, x1, y1, w/2)
		return
	}

	dx, dy := (x2-x1)/length, (y2-y1)/length
	px, py := -dy*w/2, dx*w/2

	ras.MoveTo(float32(x1+px), float32(y1+py))
	ras.LineTo(float32(x2+px), float32(y2+py))
	semicircle(ras, x2+px, y2+py, x2+dx*w/2, y2+dy*w/2, x2-px, y2-py)
	ras.LineTo(float32(x1-px), float32(y1-py))
	semicircle(ras, x1-px, y1-py, x1-dx*w/2, y1-dy*w/2, x1+px, y1+py)
	ras.ClosePath()
}

// circle fills a circle from four cubic Bézier quadrants.
func circle(ras *vector.Rasterizer, cx, cy, r float64) {
	k := kappa * r
	ras.MoveTo(float32(cx+r), float32(cy))
	ras.CubeTo(float32(cx+r), float32(cy+k), float32(cx+k), float32(cy+r), float32(cx), float32(cy+r))
	ras.CubeTo(float32(cx-k), float32(cy+r), float32(cx-r), float32(cy+k), float32(cx-r), float32(cy))
	ras.CubeTo(float32(cx-r), float32(cy-k), float32(cx-k), float32(cy-r), float32(cx), float32(cy-r))
	ras.CubeTo(float32(cx+k), float32(cy-r), float32(cx+r), float32(cy-k), float32(cx+r), float32(cy))
	ras.ClosePath()
}

// arc fills a stroked circular arc with round caps as one closed outline: the outer edge forward, a semicircle cap,
// the inner edge backward, and a semicircle cap at the start. Angles are degrees in screen coordinates — y grows
// downward, so 180 is the left of the circle, 270 the top, 360 the right, and 450 the bottom. The sweep must be
// increasing, or the caps bulge into the arc instead of away from it.
func arc(ras *vector.Rasterizer, cx, cy, r, w float64, from, to float64) {
	outer := r + w/2
	inner := r - w/2

	startOuterX, startOuterY := onCircle(cx, cy, outer, from)
	ras.MoveTo(float32(startOuterX), float32(startOuterY))
	arcSegments(ras, cx, cy, outer, from, to)

	// The cap at the far end bulges along the direction of travel
	tanX, tanY := tangent(to)
	midX, midY := onCircle(cx, cy, r, to)
	endOuterX, endOuterY := onCircle(cx, cy, outer, to)
	endInnerX, endInnerY := onCircle(cx, cy, inner, to)
	semicircle(ras, endOuterX, endOuterY, midX+tanX*w/2, midY+tanY*w/2, endInnerX, endInnerY)

	arcSegments(ras, cx, cy, inner, to, from)

	// The cap at the start bulges backward, against the direction of travel
	tanX, tanY = tangent(from)
	midX, midY = onCircle(cx, cy, r, from)
	startInnerX, startInnerY := onCircle(cx, cy, inner, from)
	semicircle(ras, startInnerX, startInnerY, midX-tanX*w/2, midY-tanY*w/2, startOuterX, startOuterY)
	ras.ClosePath()
}

// semicircle appends a half circle from one point to another, bulging through the tip. The pen must already be at
// the starting point.
func semicircle(ras *vector.Rasterizer, fromX, fromY, tipX, tipY, toX, toY float64) {
	cx, cy := (fromX+toX)/2, (fromY+toY)/2
	quarterArc(ras, cx, cy, fromX, fromY, tipX, tipY)
	quarterArc(ras, cx, cy, tipX, tipY, toX, toY)
}

// quarterArc appends a quarter circle around the center from one radius endpoint to a perpendicular one. The pen
// must already be at the starting point.
func quarterArc(ras *vector.Rasterizer, cx, cy, fromX, fromY, toX, toY float64) {
	c1x, c1y := fromX+kappa*(toX-cx), fromY+kappa*(toY-cy)
	c2x, c2y := toX+kappa*(fromX-cx), toY+kappa*(fromY-cy)
	ras.CubeTo(float32(c1x), float32(c1y), float32(c2x), float32(c2y), float32(toX), float32(toY))
}

// arcSegments appends cubic Bézier approximations of a circular arc, in steps of at most 90 degrees. The pen must
// already be at the arc's starting point.
func arcSegments(ras *vector.Rasterizer, cx, cy, r, from, to float64) {
	remaining := to - from
	current := from
	for remaining != 0 {
		step := math.Copysign(math.Min(math.Abs(remaining), 90), remaining)
		a0 := current * math.Pi / 180
		a1 := (current + step) * math.Pi / 180
		h := 4.0 / 3.0 * math.Tan((a1-a0)/4) * r

		x0, y0 := cx+r*math.Cos(a0), cy+r*math.Sin(a0)
		x3, y3 := cx+r*math.Cos(a1), cy+r*math.Sin(a1)
		x1, y1 := x0-h*math.Sin(a0), y0+h*math.Cos(a0)
		x2, y2 := x3+h*math.Sin(a1), y3-h*math.Cos(a1)
		ras.CubeTo(float32(x1), float32(y1), float32(x2), float32(y2), float32(x3), float32(y3))

		current += step
		remaining -= step
	}
}

// tangent is the unit direction of travel at an angle for an arc drawn with increasing degrees.
func tangent(degrees float64) (float64, float64) {
	radians := degrees * math.Pi / 180
	return -math.Sin(radians), math.Cos(radians)
}

func onCircle(cx, cy, r, degrees float64) (float64, float64) {
	radians := degrees * math.Pi / 180
	return cx + r*math.Cos(radians), cy + r*math.Sin(radians)
}
