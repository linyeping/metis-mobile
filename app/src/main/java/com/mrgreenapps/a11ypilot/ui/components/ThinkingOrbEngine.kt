package com.mrgreenapps.a11ypilot.ui.components

import com.mrgreenapps.a11ypilot.data.ThinkingState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Native Kotlin port of the MIT-licensed Jakubantalik/thinking-orbs geometry. */
object ThinkingOrbEngine {
    data class Dot(
        val x: Float,
        val y: Float,
        val z: Float,
        val radius: Float,
        val white: Float,
        val alpha: Float = 1f
    )

    data class Frame(val dots: List<Dot>)

    fun frame(
        state: ThinkingState,
        size: Float,
        timeSeconds: Float,
        compact: Boolean = size <= 28f
    ): Frame {
        return when (state) {
            ThinkingState.WORKING -> orbits(size, timeSeconds * if (compact) 3.9f else 1.885f, compact)
            ThinkingState.RETRIEVING -> globe(size, timeSeconds * if (compact) 2.665f else 2.015f, compact)
            ThinkingState.EXECUTING -> rubik(size, timeSeconds * if (compact) 1.95f else 1.82f, compact)
            ThinkingState.THINKING -> braid(size, timeSeconds * if (compact) 2.75f else 1.625f, compact)
            ThinkingState.ORGANIZING -> ribbon(size, timeSeconds * if (compact) 3.12f else 2.34f, compact, faceOn = false)
            ThinkingState.UNDERSTANDING -> ribbon(size, timeSeconds * if (compact) 3.78f else 3.24f, compact, faceOn = true)
            ThinkingState.COMPACTING -> morph(size, timeSeconds * if (compact) 2.08f else 2.405f, compact)
        }
    }

    private fun orbits(size: Float, t: Float, compact: Boolean): Frame {
        val center = size / 2f
        val outerRadius = center * 0.82f
        val countScale = if (compact) 0.238f else 1f
        val radiusScale = radiusScale(size) * if (compact) 2.4f else 1f
        val orbitCount = max(1, (12 * countScale).roundToInt())
        val ghostCount = max(2, (40 * countScale).roundToInt())
        val projector = projector(t * 0.12f, 0.3f, center, center, 1f)
        val dots = mutableListOf<Dot>()
        repeat(orbitCount) { orbit ->
            val h1 = hash(orbit.toFloat(), 1.7f)
            val h2 = hash(orbit.toFloat(), 5.2f)
            val h3 = hash(orbit.toFloat(), 8.9f)
            val orbitRadius = outerRadius * (0.45f + 0.52f * h1)
            val theta = h1 * 2f * PI_F
            val phi = acos(2f * h2 - 1f)
            val nx = sin(phi) * cos(theta)
            val ny = cos(phi)
            val nz = sin(phi) * sin(theta)
            var ux = -ny
            var uy = nx
            val length = max(0.000001f, sqrt(ux * ux + uy * uy))
            ux /= length
            uy /= length
            val vx = -nz * uy
            val vy = nz * ux
            val vz = nx * uy - ny * ux
            val speed = (0.25f + 0.55f * h3) * if (h3 > 0.5f) 1f else -1f
            repeat(ghostCount) { index ->
                val angle = index.toFloat() / ghostCount * 2f * PI_F
                val p = projector(
                    (ux * cos(angle) + vx * sin(angle)) * orbitRadius,
                    (uy * cos(angle) + vy * sin(angle)) * orbitRadius,
                    vz * sin(angle) * orbitRadius
                )
                val depth = (p.z / orbitRadius + 1f) / 2f
                dots += Dot(p.x, p.y, p.z, 0.9f * radiusScale, 0.72f, 0.5f * (0.4f + 0.6f * depth))
            }
            repeat(3) { particle ->
                val angle = t * speed + particle / 3f * 2f * PI_F + h2 * 6f
                val p = projector(
                    (ux * cos(angle) + vx * sin(angle)) * orbitRadius,
                    (uy * cos(angle) + vy * sin(angle)) * orbitRadius,
                    vz * sin(angle) * orbitRadius
                )
                val depth = (p.z / orbitRadius + 1f) / 2f
                dots += Dot(p.x, p.y, p.z, (1.2f + 1.6f * depth) * radiusScale, 0.3f - 0.22f * depth)
            }
        }
        return finish(dots)
    }

    private fun globe(size: Float, t: Float, compact: Boolean): Frame {
        val center = size / 2f
        val radius = center * 0.82f
        val density = if (compact) 0.105f else 0.42f
        val ringCount = max(2, (17 * sqrt(density)).roundToInt())
        val longitudeDensity = max(2, (44 * sqrt(density)).roundToInt())
        val radiusScale = radiusScale(size) * if (compact) 1.75f else 1.15f
        val spin = 0.5f
        val projector = projector(t * spin, 0.4f + 0.06f * sin(t * 0.35f), center, center, radius)
        val scan = t * (spin + (1.7f - spin) * if (compact) 4.335f else 4.08f)
        val dots = mutableListOf<Dot>()
        for (ring in 0..ringCount) {
            val latitude = -PI_F / 2f + ring.toFloat() / ringCount * PI_F
            val cosLatitude = cos(latitude)
            val longitudeCount = max(1, (abs(cosLatitude) * longitudeDensity).roundToInt())
            repeat(longitudeCount) { index ->
                val longitude = index.toFloat() / longitudeCount * 2f * PI_F
                val p = projector(cosLatitude * cos(longitude), sin(latitude), cosLatitude * sin(longitude))
                val depth = (p.z + 1f) / 2f
                val delta = angleDelta(longitude + t * spin, scan)
                val boost = exp(-(delta * delta) / 0.18f) * max(0f, p.z)
                dots += Dot(
                    p.x, p.y, p.z,
                    (0.6f + 1.7f * depth + boost) * radiusScale,
                    0.62f - 0.54f * depth,
                    0.45f + 0.55f * min(1f, boost)
                )
            }
        }
        return finish(dots)
    }

    private fun rubik(size: Float, t: Float, compact: Boolean): Frame {
        val center = size / 2f
        val radius = center * 0.82f
        val density = if (compact) 0.088f else 0.35f
        val ringCount = max(2, (15 * sqrt(density)).roundToInt())
        val longitudeDensity = max(2, (40 * sqrt(density)).roundToInt())
        val radiusScale = radiusScale(size) * if (compact) 1.9f else 1.05f
        val projector = projector(t * 0.55f, 0.35f + 0.1f * sin(t * 0.9f), center, center, radius)
        val activeBand = floor(t / 0.42f).toInt().mod(4)
        val turnProgress = smooth(min(1f, (t % 0.42f) / 0.294f)) * PI_F / 2f
        val dots = mutableListOf<Dot>()
        for (ring in 0..ringCount) {
            val latitude = -PI_F / 2f + ring.toFloat() / ringCount * PI_F
            val cosLatitude = cos(latitude)
            val longitudeCount = max(1, (abs(cosLatitude) * longitudeDensity).roundToInt())
            repeat(longitudeCount) { index ->
                val longitude = index.toFloat() / longitudeCount * 2f * PI_F
                var x = cosLatitude * cos(longitude)
                val y = sin(latitude)
                var z = cosLatitude * sin(longitude)
                val band = min(3, floor((y + 1f) * 2f).toInt())
                val active = band == activeBand
                if (active) {
                    val x2 = x * cos(turnProgress) + z * sin(turnProgress)
                    z = -x * sin(turnProgress) + z * cos(turnProgress)
                    x = x2
                }
                val p = projector(x, y, z)
                val depth = (p.z + 1f) / 2f
                dots += Dot(
                    p.x, p.y, p.z,
                    (0.6f + 1.7f * depth + if (active) 0.3f else 0f) * radiusScale,
                    0.62f - 0.54f * depth - if (active) 0.14f else 0f
                )
            }
        }
        return finish(dots)
    }

    private fun braid(size: Float, t: Float, compact: Boolean): Frame {
        val center = size / 2f
        val radius = center * 0.76f
        val countScale = if (compact) 0.1125f else 0.5f
        val radiusScale = radiusScale(size) * if (compact) 1.36f else 1f
        val projector = projector(t * 0.4f, 0.3f, center, center, 1f)
        val dots = ghostSphere(radius, projector, max(1, (150 * countScale).roundToInt()), radiusScale).toMutableList()
        val strandCount = max(3, (52 * countScale).roundToInt())
        repeat(3) { strand ->
            val phase = strand / 3f * 2f * PI_F
            repeat(strandCount) { index ->
                val u = (fraction(index.toFloat() / strandCount + t * 0.045f) * 2f - 1f) * 0.96f
                val surface = sqrt(max(0f, 1f - u * u))
                val endFade = min(1f, (1f - abs(u)) / 0.1f)
                val angle = u * PI_F * 3f + phase
                val weave = 1f + 0.075f * sin(u * PI_F * 6f + phase * 2f + t * 0.8f)
                val rr = surface * radius * weave
                val p = projector(cos(angle) * rr, u * radius * weave, sin(angle) * rr)
                val depth = (p.z / radius + 1f) / 2f
                dots += Dot(p.x, p.y, p.z, (1.2f + 1.8f * depth) * radiusScale, 0.55f - 0.45f * depth, endFade * (0.45f + 0.55f * depth))
            }
        }
        return finish(dots)
    }

    private fun ribbon(size: Float, t: Float, compact: Boolean, faceOn: Boolean): Frame {
        val center = size / 2f
        val radius = center * 0.78f
        val countScale = if (compact) if (faceOn) 0.028f else 0.051f else 0.25f
        val sizeScale = if (compact) if (faceOn) 1.622f else 1.073f else if (faceOn) 0.956f else 0.85f
        val projector = projector(0f, 0.3f, center, center, 1f)
        val dots = if (faceOn) mutableListOf() else ghostSphere(radius, projector, max(1, (150 * countScale).roundToInt()), radiusScale(size) * sizeScale).toMutableList()
        val baseLanes = max(1, (5 * sqrt(countScale)).roundToInt())
        val segments = max(8, (88 * sqrt(countScale)).roundToInt())
        val bandMultiplier = if (compact) if (faceOn) 3.968f else 4.94f else if (faceOn) 3.627f else 3.9f
        val lanes = max(1, (baseLanes * bandMultiplier).roundToInt())
        val wobMultiplier = if (faceOn) if (compact) 0.565f else 0.368f else 1f
        val planeTilt = if (faceOn) -0.3f else 0.55f
        val ux = 1f
        val uz = 0f
        val vx = -uz * sin(planeTilt)
        val vy = cos(planeTilt)
        val vz = ux * sin(planeTilt)
        val nx = -uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy
        val wobAmplitude = 0.23f * wobMultiplier
        val baseRadius = if (faceOn) radius / (1f + 0.85f * wobAmplitude) else radius
        val rs = radiusScale(size) * sizeScale
        repeat(lanes) { lane ->
            val laneOffset = (lane - (lanes - 1) / 2f) * 0.075f
            val edge = abs(lane - (lanes - 1) / 2f) / max(1f, (lanes - 1) / 2f)
            repeat(segments) { segment ->
                val angle = segment.toFloat() / segments * 2f * PI_F
                val wob = (0.16f * sin(angle * 3f - t * 1.7f + lane * 0.22f) + 0.07f * sin(angle * 5f + t * 1.1f)) * wobMultiplier
                val radial = if (faceOn) 1f + wob else 1f
                val offset = if (faceOn) laneOffset else laneOffset + wob
                val x = ux * cos(angle) + vx * sin(angle) + nx * offset
                val y = vy * sin(angle) + ny * offset
                val z = uz * cos(angle) + vz * sin(angle) + nz * offset
                val length = sqrt(x * x + y * y + z * z)
                val rr = baseRadius * radial
                val p = projector(x / length * rr, y / length * rr, z / length * rr)
                val depth = (p.z / radius + 1f) / 2f
                dots += Dot(p.x, p.y, p.z, (1.1f + 1.7f * depth) * (1f - 0.25f * edge) * rs, 0.52f - 0.44f * depth + 0.18f * edge, 0.4f + 0.6f * depth)
            }
        }
        return finish(dots)
    }

    private fun morph(size: Float, t: Float, compact: Boolean): Frame {
        val hold = 1.4f
        val duration = 0.9f
        val segment = hold + duration
        val localCycle = t % (segment * 3f)
        val shape = floor(localCycle / segment).toInt()
        val local = localCycle - shape * segment
        val amount = if (local > hold) smooth((local - hold) / duration) else 0f
        val count = max(6, (34f * if (compact) 0.53f else 0.702f).roundToInt())
        val spread = 1.45f
        val dotRadius = max(0.35f, 0.021f * 1.35f * spread * size * if (compact) 1.011f else 0.395f)
        val pulse = 1f + 0.02f * sin(local * 3.1f)
        val center = size / 2f
        val dots = List(count) { index ->
            val f = index.toFloat() / count
            val a = shapePoint(shape, f)
            val b = shapePoint((shape + 1) % 3, f)
            val x = (a.first + (b.first - a.first) * amount) * spread * pulse
            val y = (a.second + (b.second - a.second) * amount) * spread * pulse
            Dot(center + x * size, center + y * size, 0f, dotRadius, 0.1f)
        }
        return finish(dots)
    }

    private fun shapePoint(shape: Int, f: Float): Pair<Float, Float> = when (shape) {
        0 -> {
            val angle = -PI_F / 2f + f * 2f * PI_F
            Pair(cos(angle) * 0.24f, sin(angle) * 0.24f)
        }
        1 -> polygonPoint(listOf(Pair(0f, -0.26f), Pair(0.24f, 0.16f), Pair(-0.24f, 0.16f)), f)
        else -> polygonPoint(listOf(Pair(0f, -0.2f), Pair(0.2f, -0.2f), Pair(0.2f, 0.2f), Pair(-0.2f, 0.2f), Pair(-0.2f, -0.2f)), f)
    }

    private fun polygonPoint(vertices: List<Pair<Float, Float>>, fraction: Float): Pair<Float, Float> {
        val lengths = vertices.indices.map { index ->
            val a = vertices[index]
            val b = vertices[(index + 1) % vertices.size]
            sqrt((b.first - a.first).pow(2) + (b.second - a.second).pow(2))
        }
        var target = fraction * lengths.sum()
        var index = 0
        while (index < lengths.lastIndex && target > lengths[index]) {
            target -= lengths[index++]
        }
        val a = vertices[index]
        val b = vertices[(index + 1) % vertices.size]
        val amount = if (lengths[index] == 0f) 0f else min(1f, target / lengths[index])
        return Pair(a.first + (b.first - a.first) * amount, a.second + (b.second - a.second) * amount)
    }

    private fun ghostSphere(radius: Float, projector: Projector, count: Int, rs: Float): List<Dot> =
        List(count) { index ->
            val direction = fibonacciDirection(index, count)
            val p = projector(direction.x * radius, direction.y * radius, direction.z * radius)
            val depth = (p.z / radius + 1f) / 2f
            Dot(p.x, p.y, p.z, 0.8f * rs, 0.78f, 0.1f + 0.22f * depth)
        }

    private fun finish(dots: List<Dot>): Frame = Frame(
        dots.asSequence()
            .filter { it.alpha >= 0.02f }
            .map { it.copy(radius = max(0.25f, it.radius)) }
            .sortedBy { it.z }
            .toList()
    )

    private data class Point3(val x: Float, val y: Float, val z: Float)
    private fun interface Projector { operator fun invoke(x: Float, y: Float, z: Float): Point3 }

    private fun projector(yaw: Float, tilt: Float, centerX: Float, centerY: Float, scale: Float): Projector {
        val sinTilt = sin(tilt)
        val cosTilt = cos(tilt)
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)
        return Projector { x, y, z ->
            val x1 = x * cosYaw + z * sinYaw
            val z1 = -x * sinYaw + z * cosYaw
            val y1 = y * cosTilt - z1 * sinTilt
            val z2 = y * sinTilt + z1 * cosTilt
            Point3(centerX + x1 * scale, centerY - y1 * scale, z2)
        }
    }

    private fun fibonacciDirection(index: Int, count: Int): Point3 {
        val golden = PI_F * (3f - sqrt(5f))
        val y = 1f - 2f * (index + 0.5f) / count
        val radius = sqrt(1f - y * y)
        val angle = index * golden
        return Point3(radius * cos(angle), y, radius * sin(angle))
    }

    private fun radiusScale(size: Float): Float = (size / 300f).pow(0.6f)
    private fun fraction(value: Float): Float = value - floor(value)
    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
    private fun angleDelta(a: Float, b: Float): Float = atan2(sin(a - b), cos(a - b))
    private fun hash(a: Float, b: Float): Float {
        val value = sin(a * 12.9898f + b * 78.233f) * 43758.5453f
        return value - floor(value)
    }

    private const val PI_F = PI.toFloat()
}
