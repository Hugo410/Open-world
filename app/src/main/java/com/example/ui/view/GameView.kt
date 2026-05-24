package com.example.ui.view

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.GameSaveEntity
import com.example.data.model.*
import com.example.ui.viewmodel.GameViewModel
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun GameView(viewModel: GameViewModel) {
    val context = LocalContext.current
    val save by viewModel.gameSave.collectAsState()
    val stamina by viewModel.stamina.collectAsState()
    val timeOfDay by viewModel.timeOfDay.collectAsState()
    val screenShake by viewModel.screenShake.collectAsState()
    val playerYOffset by viewModel.playerYOffset.collectAsState()

    val activeDialogue by viewModel.activeDialogueNode.collectAsState()
    val isCraftOpen by viewModel.isCraftMenuOpen.collectAsState()
    val isTuningOpen by viewModel.isCustomizationMenuOpen.collectAsState()

    // Animation frame ticker
    var frameTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            frameTick = (frameTick + 1) % 360
            delay(33) // ~30hz ticking for minor UI rotations
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("game_screen")
    ) {
        // --- PRIMARY 3D CAMERA PERFORMANCE CANVAS ---
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Let user swipe on screen to rotate camera angle yaw dynamically !
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        viewModel.cameraYaw -= dragAmount.x * 0.005f
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            if (width == 0f || height == 0f) return@Canvas

            // Screen shakes
            val shakeX = if (screenShake > 0f) ((Math.random().toFloat() * 2f - 1f) * screenShake) else 0f
            val shakeY = if (screenShake > 0f) ((Math.random().toFloat() * 2f - 1f) * screenShake) else 0f

            // 1. SKYBOX CYCLIC ATMOSPHERE RENDER
            drawSkyAtmosphere(width, height, timeOfDay)

            // Calculate camera projection constants
            val px = save.playerX
            val pz = save.playerZ
            val py = viewModel.getTerrainHeight(px, pz) + playerYOffset

            // Camera trails player from behind
            val cx = px - sin(viewModel.cameraYaw) * viewModel.cameraDistance
            val cz = pz - cos(viewModel.cameraYaw) * viewModel.cameraDistance
            val cy = py + viewModel.cameraHeight

            // Center of screen projection point
            val centerX = width / 2f + shakeX
            val centerY = height * 0.55f + shakeY // Looking down slightly

            // 2. STYLIZED COORDINATE GRID FIELDS & ROADS
            drawHorizonGridAndRoads(
                cx = cx, cy = cy, cz = cz,
                yaw = viewModel.cameraYaw,
                centerX = centerX, centerY = centerY,
                width = width, height = height,
                viewModel = viewModel
            )

            // 3. STATIC LANDSCAPE SCENERY (TREES & RUINS)
            drawSceneryLandscape(
                cx = cx, cy = cy, cz = cz,
                yaw = viewModel.cameraYaw,
                centerX = centerX, centerY = centerY,
                width = width, height = height,
                frameTick = frameTick,
                viewModel = viewModel
            )

            // 4. PICKABLE ITEMS RENDER
            viewModel.itemsInWorld.forEach { item ->
                if (!item.isPicked) {
                    projectAndDrawItem(
                        item = item,
                        cx = cx, cy = cy, cz = cz,
                        yaw = viewModel.cameraYaw,
                        centerX = centerX, centerY = centerY,
                        width = width, height = height,
                        frameTick = frameTick
                    )
                }
            }

            // 5. VEHICLE PARKING / MOUNT GRAPHICS
            if (!save.inVehicle) {
                projectAndDrawVehicle(
                    vx = save.vehicleX, vz = save.vehicleZ,
                    colorHex = save.vehicleColorHex,
                    cx = cx, cy = cy, cz = cz,
                    yaw = viewModel.cameraYaw,
                    centerX = centerX, centerY = centerY,
                    width = width, height = height,
                    frameTick = frameTick,
                    viewModel = viewModel,
                    isMounted = false
                )
            }

            // 6. BOSSES ELEMENTALS
            viewModel.bosses.forEach { boss ->
                if (boss.hp > 0f) {
                    projectAndDrawBoss(
                        boss = boss,
                        cx = cx, cy = cy, cz = cz,
                        yaw = viewModel.cameraYaw,
                        centerX = centerX, centerY = centerY,
                        width = width, height = height,
                        frameTick = frameTick
                    )
                }
            }

            // 7. PLAYER WARRIOR RENDER
            if (!save.inVehicle) {
                val playerRenderHeight = py + playerYOffset
                projectAndDrawPlayer(
                    px = px, py = playerRenderHeight, pz = pz,
                    cx = cx, cy = cy, cz = cz,
                    yaw = viewModel.cameraYaw,
                    centerX = centerX, centerY = centerY,
                    width = width, height = height,
                    viewModel = viewModel,
                    frameTick = frameTick
                )
            } else {
                // If mounted, draw the active driving vehicle centering camera
                projectAndDrawVehicle(
                    vx = save.vehicleX, vz = save.vehicleZ,
                    colorHex = save.vehicleColorHex,
                    cx = cx, cy = cy, cz = cz,
                    yaw = viewModel.cameraYaw,
                    centerX = centerX, centerY = centerY,
                    width = width, height = height,
                    frameTick = frameTick,
                    viewModel = viewModel,
                    isMounted = true
                )
            }

            // 8. CUSTOM RETRO PARTICLE EFFECTS RENDER
            viewModel.particles.forEach { p ->
                projectAndDrawParticle(
                    p = p,
                    cx = cx, cy = cy, cz = cz,
                    yaw = viewModel.cameraYaw,
                    centerX = centerX, centerY = centerY,
                    width = width, height = height
                )
            }
        }

        // --- OVERLAYS AND HEADS UP DISPLAY (HUD) ---
        GameHUDOverlay(viewModel = viewModel, save = save, stamina = stamina)

        // --- TOUCH INPUT GAMEPLAY CONTROLS PANEL ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Left Joystick
                VirtualJoystickComponent(
                    onJoystickMoved = { x, y ->
                        viewModel.joypadX = x
                        viewModel.joypadY = y
                    }
                )

                // Right Action Controls
                VirtualActionButtonsGroup(
                    viewModel = viewModel,
                    inVehicle = save.inVehicle
                )
            }
        }

        // --- BRANCHING NPC DIALOGUE OVERLAY POP-UP ---
        AnimatedVisibility(
            visible = activeDialogue != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            activeDialogue?.let { node ->
                DialoguePanelComponent(
                    node = node,
                    inventoryMap = save.getInventoryMap(),
                    onOptionSelected = { option -> viewModel.selectDialogueOption(option) },
                    onClose = { viewModel.closeDialogue() }
                )
            }
        }

        // --- COMPLETE CRAFT WORKBENCH DIALOG PANEL ---
        AnimatedVisibility(
            visible = isCraftOpen,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .fillMaxWidth(0.85f)
                .align(Alignment.Center)
        ) {
            CraftWorkbenchPanel(viewModel = viewModel, save = save)
        }

        // --- VEHICLE COLOR TUNING AND PART UPGRADES SHOP ---
        AnimatedVisibility(
            visible = isTuningOpen,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .fillMaxWidth(0.85f)
                .align(Alignment.Center)
        ) {
            VehicleTuningShopPanel(viewModel = viewModel, save = save)
        }
    }
}

// -------------------------------------------------------------
// CORE PROJECTION MATHEMATICS AND VECTOR GRAPHICS UNDER JETPACK COMPOSE DRAW SCOPE
// -------------------------------------------------------------

fun DrawScope.drawSkyAtmosphere(width: Float, height: Float, time: Float) {
    // Beautiful sky gradient transition (Night indigo, Sunset burnt orange, Day golden azure)
    val skyColors = when {
        time < 5f -> listOf(Color(0xFF0D0B1F), Color(0xFF1E1B3E)) // Indigo night
        time < 8f -> listOf(Color(0xFF1E1B3E), Color(0xFFE67E22), Color(0xFFF39C12)) // Golden Sunrise
        time < 17f -> listOf(Color(0xFF42A5F5), Color(0xFF90CAF9)) // Bright Azure Day
        time < 20f -> listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFFFF5722)) // Copper twilight sunset
        else -> listOf(Color(0xFF0D0B1F), Color(0xFF16142E)) // Night
    }

    drawRect(
        brush = Brush.verticalGradient(skyColors),
        size = Size(width, height)
    )

    // Draw dynamic glittering cosmos stars at night
    if (time < 6f || time > 19f) {
        val count = 25
        for (i in 0 until count) {
            val starX = (sin(i * 123.4f) * 0.5f + 0.5f) * width
            val starY = (cos(i * 456.7f) * 0.5f + 0.5f) * height * 0.45f
            val starAlpha = (sin((time * 1.5f) + i) * 0.5f + 0.5f)
            drawCircle(Color.White.copy(alpha = starAlpha.coerceAtMost(1f)), radius = 2f, center = Offset(starX, starY))
        }
    }

    // Draw stylized sun or moon moving in orbit
    val celestialRotationAngle = (time / 24f) * 2f * Math.PI - Math.PI / 2f
    val sunDistance = width * 0.4f
    val centerSkyX = width * 0.5f
    val centerSkyY = height * 0.6f

    val bodyX = centerSkyX + cos(celestialRotationAngle).toFloat() * sunDistance
    val bodyY = centerSkyY + sin(celestialRotationAngle).toFloat() * sunDistance * 0.6f

    if (bodyY < height * 0.55f) { // If above horizon
        val isDay = time in 6f..18f
        val glowColor = if (isDay) Color(0xFFFFEB3B).copy(alpha = 0.3f) else Color(0xFFECEFF1).copy(alpha = 0.15f)
        val solidColor = if (isDay) Color(0xFFFFEB3B) else Color(0xFFECEFF1)
        val radius = if (isDay) 35f else 22f

        drawCircle(glowColor, radius = radius * 2.2f, center = Offset(bodyX, bodyY))
        drawCircle(solidColor, radius = radius, center = Offset(bodyX, bodyY))
    }
}

fun DrawScope.drawHorizonGridAndRoads(
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float,
    viewModel: GameViewModel
) {
    // 1. Draw solid ground horizon block with realistic depth shading
    val groundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF1E3A1A), Color(0xFF33691E), Color(0xFF558B2F))
    )
    drawRect(
        brush = groundGradient,
        topLeft = Offset(0f, centerY),
        size = Size(width, height - centerY)
    )

    // Horizon line delimiter separating space
    drawLine(
        Color(0xFF0F9D58),
        start = Offset(0f, centerY),
        end = Offset(width, centerY),
        strokeWidth = 3f
    )

    // 2. Draw Scenic High-speed Highways projected in 3D
    // Roads are represented as elegant intersecting splines
    val numLanes = 3
    for (lane in 0 until numLanes) {
        val roadPoints = mutableListOf<Offset>()
        // Calculate coordinate points on the looping road
        var zVal = 5f
        while (zVal <= 120f) {
            val rX = if (lane == 0) -15f else if (lane == 1) 15f else 0f
            // Road z coordinate curves in world space
            val rZ = zVal + (sin(zVal * 0.05f) * 10f)

            val proj = project3D(rX, viewModel.getTerrainHeight(rX, rZ), rZ, cx, cy, cz, yaw, centerX, centerY, width)
            if (proj != null) {
                roadPoints.add(proj)
            }
            zVal += 5f
        }

        if (roadPoints.size >= 2) {
            val roadPath = Path()
            roadPath.moveTo(roadPoints[0].x, roadPoints[0].y)
            for (i in 1 until roadPoints.size) {
                roadPath.lineTo(roadPoints[i].x, roadPoints[i].y)
            }

            // Draw asphalt road strip
            val strokeThick = 2400f / (lane + 1)
            drawPath(
                path = roadPath,
                color = Color(0xFF37474F).copy(alpha = 0.8f),
                style = Stroke(width = 18f, cap = StrokeCap.Round)
            )
            // Draw yellow dash indicator line
            drawPath(
                path = roadPath,
                color = Color(0xFFFFCA28).copy(alpha = 0.6f),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }
}

fun DrawScope.drawSceneryLandscape(
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float,
    frameTick: Int,
    viewModel: GameViewModel
) {
    // Render several pine trees across landscape
    val forestNodes = listOf(
        Offset(-40f, 25f), Offset(-28f, -12f), Offset(18f, -22f),
        Offset(30f, 40f), Offset(45f, -30f), Offset(-35f, -40f),
        Offset(12f, 20f), Offset(-18f, 35f), Offset(55f, 15f),
        Offset(-50f, 10f)
    )

    forestNodes.forEachIndexed { i, offsetLoc ->
        val wx = offsetLoc.x
        val wz = offsetLoc.y
        val wy = viewModel.getTerrainHeight(wx, wz)

        val proj = project3D(wx, wy, wz, cx, cy, cz, yaw, centerX, centerY, width)
        if (proj != null) {
            val distToCam = sqrt((wx - cx) * (wx - cx) + (wz - cz) * (wz - cz))
            if (distToCam > 1.5f) {
                val sizeScale = 450f / distToCam
                if (sizeScale in 1f..180f) {
                    // Draw trunk
                    val trWidth = sizeScale * 0.15f
                    val trHeight = sizeScale * 0.6f
                    drawRect(
                        Color(0xFF5D4037),
                        topLeft = Offset(proj.x - trWidth/2, proj.y - trHeight),
                        size = Size(trWidth, trHeight)
                    )

                    // Draw green multi-layered foliage cap swaying slightly
                    val sway = sin((frameTick + i * 33) * 0.05f) * 2.5f
                    val foliageCap = Path()
                    foliageCap.moveTo(proj.x + sway, proj.y - trHeight - sizeScale * 1.5f)
                    foliageCap.lineTo(proj.x - sizeScale * 0.7f, proj.y - trHeight * 0.9f)
                    foliageCap.lineTo(proj.x + sizeScale * 0.7f, proj.y - trHeight * 0.9f)
                    foliageCap.close()

                    drawPath(foliageCap, Color(0xFF2E7D32).copy(alpha = 0.9f))
                }
            }
        }
    }

    // Render static ruins center pillar with names
    viewModel.obstacles.forEach { obstacle ->
        val wy = viewModel.getTerrainHeight(obstacle.x, obstacle.z)
        val proj = project3D(obstacle.x, wy, obstacle.z, cx, cy, cz, yaw, centerX, centerY, width)
        if (proj != null) {
            val dist = sqrt((obstacle.x-cx)*(obstacle.x-cx)+(obstacle.z-cz)*(obstacle.z-cz))
            val rectScale = 300f / dist
            if (rectScale in 1f..200f) {
                // Draw grey stone column cylinder
                drawRect(
                    Color(0xFF78909C),
                    topLeft = Offset(proj.x - rectScale * obstacle.radius * 0.2f, proj.y - rectScale * 4f),
                    size = Size(rectScale * obstacle.radius * 0.4f, rectScale * 4f)
                )
                // Draw cracks
                drawLine(
                    Color.DarkGray,
                    start = Offset(proj.x, proj.y - rectScale * 4f),
                    end = Offset(proj.x + rectScale * 0.2f, proj.y - rectScale * 1.5f)
                )
            }
        }
    }

    // Render visual glowing NPC indicators
    drawNpcIndicator("NPC_GEDEON", "Gédéon (Mécanicien)", -10f, -40f, Color(0xFFFF9800), cx, cy, cz, yaw, centerX, centerY, width, viewModel)
    drawNpcIndicator("NPC_ARCHIBALD", "Archibald (Forgeron)", -35f, 15f, Color(0xFFFF5722), cx, cy, cz, yaw, centerX, centerY, width, viewModel)
    drawNpcIndicator("NPC_CELESTE", "Céleste (Prêtresse)", 40f, 30f, Color(0xFF2196F3), cx, cy, cz, yaw, centerX, centerY, width, viewModel)
}

fun DrawScope.drawNpcIndicator(
    npcId: String, name: String, wx: Float, wz: Float, color: Color,
    cx: Float, cy: Float, cz: Float, yaw: Float,
    centerX: Float, centerY: Float, width: Float, viewModel: GameViewModel
) {
    val wy = viewModel.getTerrainHeight(wx, wz)
    val proj = project3D(wx, wy, wz, cx, cy, cz, yaw, centerX, centerY, width)
    if (proj != null) {
        val dist = sqrt((wx-cx)*(wx-cx) + (wz-cz)*(wz-cz))
        val sc = 400f / dist
        if (sc in 1f..200f) {
            // Shiny giant vertical beacon of starlight !
            drawRect(
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), Color.Transparent)),
                topLeft = Offset(proj.x - sc * 0.5f, proj.y - sc * 12f),
                size = Size(sc, sc * 12f)
            )

            // Draw glowing core sphere
            drawCircle(color, radius = sc * 0.8f, center = Offset(proj.x, proj.y - sc * 0.6f))

            // Text tag labeling
            drawContextText(name, proj.x, proj.y - sc * 3.5f, color = color)
        }
    }
}

fun DrawScope.projectAndDrawItem(
    item: GameItem,
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float,
    frameTick: Int
) {
    val proj = project3D(item.x, item.y, item.z, cx, cy, cz, yaw, centerX, centerY, width)
    if (proj != null) {
        val dist = sqrt((item.x-cx)*(item.x-cx) + (item.z-cz)*(item.z-cz))
        val sc = 300f / dist
        if (sc in 1f..150f) {
            // Spin and hover items using ticks
            val rotOffset = (frameTick * 3f) * Math.PI / 180f
            val bounceY = yrBounce(frameTick) * sc * 0.2f

            val itemColor = when (item.type) {
                ItemType.HERBE -> Color(0xFF81C784)
                ItemType.FER -> Color(0xFF90A4AE)
                ItemType.BOIS -> Color(0xFFBCAAA4)
                ItemType.BATTERIE -> Color(0xFF80DEEA)
                ItemType.RUBIS -> Color(0xFFFF8A80)
                ItemType.COEUR -> Color(0xFFF48FB1)
            }

            // Draw floating glowing diamond shape
            val path = Path()
            path.moveTo(proj.x, proj.y - sc * 1.5f + bounceY)
            path.lineTo(proj.x + sc * 0.8f, proj.y - sc * 0.8f + bounceY)
            path.lineTo(proj.x, proj.y + bounceY)
            path.lineTo(proj.x - sc * 0.8f, proj.y - sc * 0.8f + bounceY)
            path.close()

            drawPath(path, itemColor)
            drawCircle(Color.White.copy(alpha = 0.6f), radius = sc * 0.25f, center = Offset(proj.x, proj.y - sc * 0.8f + bounceY))

            // Label
            drawContextText(item.type.icon, proj.x, proj.y - sc * 2.5f + bounceY)
        }
    }
}

fun DrawScope.projectAndDrawBoss(
    boss: GameBoss,
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float,
    frameTick: Int
) {
    val proj = project3D(boss.x, boss.y, boss.z, cx, cy, cz, yaw, centerX, centerY, width)
    if (proj != null) {
        val dist = sqrt((boss.x-cx)*(boss.x-cx)+(boss.z-cz)*(boss.z-cz))
        val sc = 800f / dist
        if (sc in 1f..350f) {
            val bossColor = if (boss.type == BossType.GOLIATH) Color(0xFF5D4037) else Color(0xFFD84315)
            val stateBounce = sin(frameTick * 0.15f) * sc * 0.1f

            // 1. Draw Ground Danger Warning radius circle if charging attack!
            if (boss.state == "PURSUING" && boss.attackTimer > 60) {
                val warnAlpha = (boss.attackTimer - 60) / 50f
                drawCircle(
                    color = Color.Red.copy(alpha = warnAlpha.coerceAtMost(0.3f)),
                    radius = sc * 4f,
                    center = proj
                )
                drawCircle(
                    color = Color.Red.copy(alpha = warnAlpha.coerceAtMost(0.7f)),
                    radius = sc * 4f,
                    center = proj,
                    style = Stroke(width = 3f)
                )
            }

            // 2. Draw Colossus parts (Body, limbs)
            // Major main trunk torso body
            drawRect(
                bossColor,
                topLeft = Offset(proj.x - sc * 1.5f, proj.y - sc * 3.8f + stateBounce),
                size = Size(sc * 3f, sc * 3f)
            )

            // Arms / Shields
            drawRect(
                Color.DarkGray,
                topLeft = Offset(proj.x - sc * 2.3f, proj.y - sc * 3.2f + sin(frameTick * 0.1f) * sc * 0.3f),
                size = Size(sc * 0.7f, sc * 1.8f)
            )
            drawRect(
                Color.DarkGray,
                topLeft = Offset(proj.x + sc * 1.6f, proj.y - sc * 3.2f - sin(frameTick * 0.1f) * sc * 0.3f),
                size = Size(sc * 0.7f, sc * 1.8f)
            )

            // Dynamic Red cybernetic glowing eye for Sentinel tripod
            val eyeColor = if (boss.type == BossType.SENTINELLE) Color.Red else Color(0xFFFFB300)
            drawCircle(
                color = eyeColor,
                radius = sc * 0.4f,
                center = Offset(proj.x, proj.y - sc * 2.8f + stateBounce)
            )

            // HP bar overlay
            val hpRatio = boss.hp / boss.maxHp
            val barW = sc * 4.5f
            val barH = sc * 0.3f
            drawRect(
                Color.Black,
                topLeft = Offset(proj.x - barW/2, proj.y - sc * 4.8f),
                size = Size(barW, barH)
            )
            drawRect(
                if (hpRatio > 0.5f) Color(0xFF4CAF50) else Color(0xFFF44336),
                topLeft = Offset(proj.x - barW/2 + 2f, proj.y - sc * 4.8f + 2f),
                size = Size((barW - 4f) * hpRatio, barH - 4f)
            )

            // Boss label text
            drawContextText(boss.name, proj.x, proj.y - sc * 5.2f, color = Color.White, size = 12f)
        }
    }
}

fun DrawScope.projectAndDrawPlayer(
    px: Float, py: Float, pz: Float,
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float,
    viewModel: GameViewModel,
    frameTick: Int
) {
    val proj = project3D(px, py, pz, cx, cy, cz, yaw, centerX, centerY, width)
    if (proj != null) {
        val dist = sqrt((px-cx)*(px-cx)+(pz-cz)*(pz-cz))
        val sc = 600f / dist
        if (sc in 1f..180f) {
            val isActionSec = viewModel.isAttacking
            val actionSwayX = if (isActionSec) (sin(frameTick * 0.8f) * sc * 0.8f) else 0f

            // A. Draw green leather warrior tunic body / capsule shape
            drawCircle(
                color = Color(0xFF43A047),
                radius = sc * 0.7f,
                center = Offset(proj.x, proj.y - sc * 1.8f)
            )
            // Head / Skin
            drawCircle(
                color = Color(0xFFFFCC80),
                radius = sc * 0.45f,
                center = Offset(proj.x, proj.y - sc * 2.6f)
            )
            // Cute Link hair / Cap
            val capPath = Path()
            capPath.moveTo(proj.x - sc * 0.5f, proj.y - sc * 2.8f)
            capPath.lineTo(proj.x + sc * 0.5f, proj.y - sc * 2.8f)
            capPath.lineTo(proj.x, proj.y - sc * 3.4f)
            capPath.close()
            drawPath(capPath, Color(0xFF2E7D32))

            // B. Draw active weapons and shield depending on player states
            // Left Arm: Shield blocking posture
            val shieldColor = if (viewModel.isShieldBlocking) Color(0xFF00B0FF) else Color(0xFF8D6E63)
            val shieldOffset = if (viewModel.isShieldBlocking) sc * 0.8f else sc * 0.4f
            drawRect(
                color = shieldColor,
                topLeft = Offset(proj.x - sc * 1.0f, proj.y - sc * 1.6f),
                size = Size(sc * 0.3f, sc * 1.1f)
            )

            // Right Arm: Swing sword attack vector!
            val swordColor = if (viewModel.activeWeaponDamage > 25f) Color(0xFFD500F9) else Color(0xFFB0BEC5)
            drawLine(
                color = swordColor,
                start = Offset(proj.x + sc * 0.6f, proj.y - sc * 1.8f),
                end = Offset(proj.x + sc * 1.4f + actionSwayX, proj.y - sc * 2.4f - actionSwayX),
                strokeWidth = sc * 0.16f,
                cap = StrokeCap.Round
            )
        }
    }
}

fun DrawScope.projectAndDrawVehicle(
    vx: Float, vz: Float,
    colorHex: String,
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float,
    frameTick: Int,
    viewModel: GameViewModel,
    isMounted: Boolean
) {
    val vy = viewModel.getTerrainHeight(vx, vz)
    val proj = project3D(vx, vy, vz, cx, cy, cz, yaw, centerX, centerY, width)
    if (proj != null) {
        val dist = sqrt((vx-cx)*(vx-cx) + (vz-cz)*(vz-cz))
        val sc = 600f / dist
        if (sc in 1f..320f) {
            val hullColor = Color(android.graphics.Color.parseColor(colorHex))
            val rollBounce = sin(frameTick * 0.25f) * sc * 0.05f

            // 1. Draw chassis wheels (ATV Offroader buggy chunky tires!)
            drawCircle(Color.Black, radius = sc * 0.45f, center = Offset(proj.x - sc * 1.2f, proj.y - sc * 0.45f))
            drawCircle(Color.DarkGray, radius = sc * 0.22f, center = Offset(proj.x - sc * 1.2f, proj.y - sc * 0.45f))

            drawCircle(Color.Black, radius = sc * 0.45f, center = Offset(proj.x + sc * 1.2f, proj.y - sc * 0.45f))
            drawCircle(Color.DarkGray, radius = sc * 0.22f, center = Offset(proj.x + sc * 1.2f, proj.y - sc * 0.45f))

            // 2. Draw vehicle aerodynamic bumper shield
            val bLvl = viewModel.gameSave.value.vehicleBumperLevel
            val bumperColor = if (bLvl > 1) Color.LightGray else Color(0xFF455A64)
            drawRect(
                color = bumperColor,
                topLeft = Offset(proj.x - sc * 1.5f, proj.y - sc * 0.8f),
                size = Size(sc * 3.0f, sc * 0.3f)
            )

            // 3. Draw Main Hull customizable neon body cabin!
            val cabinPath = Path()
            cabinPath.moveTo(proj.x - sc * 1.3f, proj.y - sc * 0.8f + rollBounce)
            cabinPath.lineTo(proj.x - sc * 0.8f, proj.y - sc * 1.8f + rollBounce)
            cabinPath.lineTo(proj.x + sc * 0.8f, proj.y - sc * 1.8f + rollBounce)
            cabinPath.lineTo(proj.x + sc * 1.3f, proj.y - sc * 0.8f + rollBounce)
            cabinPath.close()

            drawPath(cabinPath, hullColor.copy(alpha = 0.9f))

            // Spoiler wing representing high performance speed engineering
            drawLine(
                Color.Black,
                start = Offset(proj.x - sc * 1.0f, proj.y - sc * 1.8f + rollBounce),
                end = Offset(proj.x - sc * 1.3f, proj.y - sc * 2.3f + rollBounce),
                strokeWidth = sc * 0.15f
            )
            drawRect(
                Color.Red,
                topLeft = Offset(proj.x - sc * 1.6f, proj.y - sc * 2.5f + rollBounce),
                size = Size(sc * 0.8f, sc * 0.25f)
            )

            // Neon glowing headlights projection
            val beamColor = Color(0xFFFFFF8D).copy(alpha = 0.4f)
            val beamPath = Path()
            beamPath.moveTo(proj.x + sc * 1.2f, proj.y - sc * 0.8f)
            beamPath.lineTo(proj.x + sc * 4.5f, proj.y + sc * 1.2f)
            beamPath.lineTo(proj.x + sc * 3.2f, proj.y + sc * 2.5f)
            beamPath.close()
            drawPath(beamPath, beamColor)

            // Visual label indicating driving prompt
            if (!isMounted) {
                drawContextText("Buggy ( Monter : Entrer )", proj.x, proj.y - sc * 2.8f + rollBounce, color = Color(0xFFFFB300))
            } else {
                // If mounted, draw tiny driver head inside cockpit cabin!
                drawCircle(Color(0xFFFFCC80), radius = sc * 0.26f, center = Offset(proj.x, proj.y - sc * 1.2f + rollBounce))
            }
        }
    }
}

fun DrawScope.projectAndDrawParticle(
    p: Particle,
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float, height: Float
) {
    val proj = project3D(p.x, p.y, p.z, cx, cy, cz, yaw, centerX, centerY, width)
    if (proj != null) {
        val dist = sqrt((p.x-cx)*(p.x-cx) + (p.z-cz)*(p.z-cz))
        val sc = 400f / dist
        if (sc in 1f..250f) {
            val finalAlpha = p.alpha.coerceIn(0f, 1f)

            if (p.text != null) {
                // Draw floating stylized words or values
                drawContextText(p.text, proj.x, proj.y, color = p.color.copy(alpha = finalAlpha), size = 18f)
            } else {
                // Draw physical dots / rings
                drawCircle(
                    color = p.color.copy(alpha = finalAlpha),
                    radius = p.size * (sc * 0.12f).coerceAtLeast(0.5f),
                    center = proj
                )
            }
        }
    }
}

// 3D coordinate transformation calculations to screen orthographic viewport projection
fun project3D(
    wx: Float, wy: Float, wz: Float,
    cx: Float, cy: Float, cz: Float,
    yaw: Float,
    centerX: Float, centerY: Float,
    width: Float
): Offset? {
    // Translate relative to camera vector origins
    val rx = wx - cx
    val ry = wy - cy
    val rz = wz - cz

    // Transform points rotating matching yaw angle
    val rotYawX = rx * cos(-yaw) - rz * sin(-yaw)
    val rotYawZ = rx * sin(-yaw) + rz * cos(-yaw)
    val rotYawY = ry

    // Filter points positioned in camera backward hemisphere index direction
    if (rotYawZ <= 1.5f) return null // Clipped

    // Perspective focal coefficient transformation scaling
    val fov = 1.0f
    val scale = width * (fov / rotYawZ)

    val sx = centerX + rotYawX * scale
    val sy = centerY - rotYawY * scale // Invert vertical direction

    return Offset(sx, sy)
}

fun yrBounce(frame: Int): Float {
    return sin(frame * 0.3f)
}

fun DrawScope.drawContextText(
    text: String, x: Float, y: Float,
    color: Color = Color.White, size: Float = 10f
) {
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = size * 3f
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    drawContextNative(text, x, y, paint)
}

fun DrawScope.drawContextNative(text: String, x: Float, y: Float, paint: Paint) {
    drawContextNativeCanvas { canvas ->
        canvas.drawText(text, x, y, paint)
    }
}

inline fun DrawScope.drawContextNativeCanvas(block: (android.graphics.Canvas) -> Unit) {
    drawContext.canvas.nativeCanvas.let(block)
}

// -------------------------------------------------------------
// HEADS UP DISPLAY COHESION SYSTEM (HEARTS, STAMINA, CURRENCY, MINIMAP)
// -------------------------------------------------------------

@Composable
fun GameHUDOverlay(viewModel: GameViewModel, save: GameSaveEntity, stamina: Float) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP STATUS BAR HUD ITEMS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Heart Container bars
            Row(
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Heart containers
                val numHearts = 5
                val currentHeartValue = (save.hp / save.maxHp) * numHearts
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until numHearts) {
                        val iconHeart = if (i < currentHeartValue.toInt()) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Filled.FavoriteBorder
                        }
                        Icon(
                            imageVector = iconHeart,
                            contentDescription = "Cœur de Vie",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                // Rupees indicator
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Rubis argent",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${save.rupees} 💎",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // Stamina Green Wheel Circle
            Box(
                modifier = Modifier
                    .shadow(4.dp, CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .size(54.dp)
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { stamina / 100f },
                    color = Color(0xFF00E676),
                    trackColor = Color.DarkGray,
                    strokeWidth = 4.dp,
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = "⚡",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            // MINI RADAR RADIAL COMPASS
            Box(
                modifier = Modifier
                    .shadow(5.dp, RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .border(2.dp, Color(0xFF90A4AE).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .size(100.dp)
                    .padding(4.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.width / 2f
                    // Center local radar coordinates matching player
                    drawCircle(Color.DarkGray.copy(alpha = 0.4f), radius = r)
                    drawCircle(Color.LightGray.copy(alpha = 0.3f), radius = r * 0.5f)

                    // Draw roads on radar
                    drawLine(Color.Gray.copy(alpha = 0.4f), start = Offset(0f, r), end = Offset(size.width, r))
                    drawLine(Color.Gray.copy(alpha = 0.4f), start = Offset(r, 0f), end = Offset(r, size.height))

                    // Represent elements as dots relative to player range -150 to +150
                    val px = save.playerX
                    val pz = save.playerZ

                    // 1. Vehicle position
                    if (!save.inVehicle) {
                        val dxV = (save.vehicleX - px) / 150f * r
                        val dzV = -(save.vehicleZ - pz) / 150f * r
                        drawCircle(Color(0xFFFF9800), radius = 4f, center = Offset(r + dxV, r + dzV))
                    }

                    // 2. Boss positions
                    viewModel.bosses.forEach { boss ->
                        if (boss.hp > 0) {
                            val dxB = (boss.x - px) / 150f * r
                            val dzB = -(boss.z - pz) / 150f * r
                            drawCircle(Color.Red, radius = 5f, center = Offset(r + dxB, r + dzB))
                        }
                    }

                    // 3. Center Player pointer
                    drawCircle(Color(0xFF00E5FF), radius = 5f, center = Offset(r, r))
                }
            }
        }

        // QUEST PROGRESS BANNER AT MIDDLE TOP
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .align(Alignment.CenterHorizontally)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "🎯 QUÊTE ACTIVE",
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                val questMsg = when (save.currentQuestId) {
                    "REVEIL" -> "Trouver Gédéon le mécanicien au Sud-Sud-Ouest (x: -10, z: -40) pour réactiver l'aventure."
                    "REVEIL_BATTERIE_READY" -> "Rapporter la 'Batterie Ancienne' ramassée au Nord-Est à Gédéon pour gonfler le Buggy."
                    "BOSS_HUNT" -> "Terrasser les deux monstres sauvages. Aide toi des pare-chocs ram-bélier de ton véhicule !"
                    "WORLD_SAVED" -> "Tous les titans sont vaincus. Les terres de Légendes Sauvages célèbrent votre vaillance !"
                    else -> "Explorez les contrées."
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = questMsg,
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

// -------------------------------------------------------------
// VIRTUAL INTERACTIVE CONTROLS JOYPAD & ACTIONS BUTTONS OVERLAYS
// -------------------------------------------------------------

@Composable
fun VirtualJoystickComponent(onJoystickMoved: (Float, Float) -> Unit) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val maxRadiusPx = 110f

    Box(
        modifier = Modifier
            .size(130.dp)
            .shadow(4.dp, CircleShape)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            .border(2.dp, Color(0xFF607D8B).copy(alpha = 0.4f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        offsetX = 0f
                        offsetY = 0f
                        onJoystickMoved(0f, 0f)
                    },
                    onDragCancel = {
                        offsetX = 0f
                        offsetY = 0f
                        onJoystickMoved(0f, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val newX = offsetX + dragAmount.x
                    val newY = offsetY + dragAmount.y
                    val dist = sqrt(newX * newX + newY * newY)

                    if (dist < maxRadiusPx) {
                        offsetX = newX
                        offsetY = newY
                    } else {
                        offsetX = (newX / dist) * maxRadiusPx
                        offsetY = (newY / dist) * maxRadiusPx
                    }
                    // Emmit values -1.0 to +1.0
                    onJoystickMoved(offsetX / maxRadiusPx, offsetY / maxRadiusPx)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Inner handle thumb
        Box(
            modifier = Modifier
                .offset(
                    x = (offsetX / maxRadiusPx * 30).dp,
                    y = (offsetY / maxRadiusPx * 30).dp
                )
                .size(54.dp)
                .shadow(6.dp, CircleShape)
                .background(Color(0xFF90A4AE), CircleShape)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
fun VirtualActionButtonsGroup(viewModel: GameViewModel, inVehicle: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Dialogue Check button - Talk if near NPCs
        val context = LocalContext.current
        Button(
            onClick = {
                // Find nearest NPC
                val px = viewModel.gameSave.value.playerX
                val pz = viewModel.gameSave.value.playerZ
                val gDist = sqrt((px - (-10f))*(px - (-10f)) + (pz - (-40f))*(pz - (-40f)))
                val aDist = sqrt((px - (-35f))*(px - (-35f)) + (pz - 15f)*(pz - 15f))
                val cDist = sqrt((px - 40f)*(px - 40f) + (pz - 30f)*(pz - 30f))

                when {
                    gDist < 8f -> viewModel.openNpcDialogue("NPC_GEDEON")
                    aDist < 8f -> viewModel.openNpcDialogue("NPC_ARCHIBALD")
                    cDist < 8f -> viewModel.openNpcDialogue("NPC_CELESTE")
                    else -> viewModel.triggerSwordAttack()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
            modifier = Modifier
                .size(60.dp)
                .shadow(5.dp, CircleShape)
                .testTag("action_talk_attack")
        ) {
            Icon(Icons.Filled.AccountBox, contentDescription = "Parler / Attaque", tint = Color.White)
        }

        if (!inVehicle) {
            // MOUNT VEHICLE RIDE BUTTON
            Button(
                onClick = { viewModel.toggleVehicleRiding() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)),
                modifier = Modifier
                    .size(64.dp)
                    .shadow(5.dp, CircleShape)
                    .testTag("action_mount")
            ) {
                Icon(Icons.Filled.Build, contentDescription = "Monter bolide", tint = Color.White)
            }

            // MELEE SWORD ATTACK BUTTON
            Button(
                onClick = { viewModel.triggerSwordAttack() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                modifier = Modifier
                    .size(68.dp)
                    .shadow(7.dp, CircleShape)
                    .testTag("action_sword")
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Épée Attaquer", tint = Color.White)
            }
        } else {
            // UNMOUNT / PARK BUGGY VEHICLE BUTTON
            Button(
                onClick = { viewModel.toggleVehicleRiding() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                modifier = Modifier
                    .size(62.dp)
                    .shadow(5.dp, CircleShape)
                    .testTag("action_unmount")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Descendre bolide", tint = Color.White)
            }

            // TURBO BOOST ENGINE JUMP SEISM CONTROLS
            Button(
                onClick = { viewModel.triggerPlayerJump() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                modifier = Modifier
                    .size(68.dp)
                    .shadow(8.dp, CircleShape)
                    .testTag("action_boost")
            ) {
                Text("BOOST", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
            }
        }

        // FOOT MODE BLOCKS OR GENERAL JUMPS
        Button(
            onClick = { viewModel.triggerPlayerJump() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2)),
            modifier = Modifier
                .size(60.dp)
                .shadow(5.dp, CircleShape)
                .testTag("action_jump")
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Sauter", tint = Color.White)
        }
    }
}

// -------------------------------------------------------------
// BRANCHING TYPES DIALOGUE SYSTEM LAYOUT
// -------------------------------------------------------------

@Composable
fun DialoguePanelComponent(
    node: DialogueNode,
    inventoryMap: Map<String, Int>,
    onOptionSelected: (DialogueOption) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color(0xFFFFCA28), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF13141C).copy(alpha = 0.95f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // NPC Portraits simulation drawing
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(2.dp, CircleShape)
                    .background(Color.DarkGray, CircleShape)
                    .border(2.dp, Color(0xFFFFCA28), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val emoji = when {
                    node.npcName.contains("Gédéon") -> "🔧"
                    node.npcName.contains("Archibald") -> "⚒️"
                    else -> "🔮"
                }
                Text(emoji, fontSize = 32.sp)
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.npcName,
                        color = Color(0xFFFFCA28),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer dialogue", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = node.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Render option choices
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    node.options.forEach { opt ->
                        // Validate requirement constraints if needed
                        var display = true
                        if (opt.requiredQuestId == "HAS_BATTERY") {
                            val batQty = inventoryMap[ItemType.BATTERIE.name] ?: 0
                            if (batQty <= 0) {
                                display = false // Hidden option
                            }
                        }

                        if (display) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF263238))
                                    .clickable { onOptionSelected(opt) }
                                    .padding(vertical = 10.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowRight,
                                    contentDescription = "Option go",
                                    tint = Color(0xFFFFCA28),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = opt.text,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// CRAFTING WORKBENCH SYSTEM (CRITICAL CAPABILITY)
// -------------------------------------------------------------

@Composable
fun CraftWorkbenchPanel(viewModel: GameViewModel, save: GameSaveEntity) {
    val currentInv = save.getInventoryMap()

    Card(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚒️", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Forge Sauvage & Alchimie",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                IconButton(onClick = { viewModel.closeCraftMenu() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Show current materials summary inventory
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B1E29), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ItemType.values().forEach { type ->
                    val owned = currentInv[type.name] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(type.icon, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${type.displayName}: $owned",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(viewModel.craftRecipes) { recipe ->
                    var isAffordable = true
                    val costListString = recipe.cost.map { (key, qty) ->
                        val owned = currentInv[key.name] ?: 0
                        if (owned < qty) isAffordable = false
                        "${key.icon} $owned/$qty"
                    }.joinToString("  |  ")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF181B24), RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                if (isAffordable) Color(0xFF4CAF50).copy(alpha = 0.4f) else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(recipe.resultIcon, fontSize = 24.sp)
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(recipe.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(recipe.resultDescription, color = Color.Gray, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Matériaux : $costListString",
                                color = if (isAffordable) Color(0xFF81C784) else Color(0xFFE57373),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { viewModel.craftItem(recipe) },
                            enabled = isAffordable,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF388E3C),
                                disabledContainerColor = Color.DarkGray
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("FORGER", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// VEHICLE TUNING SHOP PANEL (NEON COLOR + ENGINE LEVEL UP)
// -------------------------------------------------------------

@Composable
fun VehicleTuningShopPanel(viewModel: GameViewModel, save: GameSaveEntity) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏎️", fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Atelier de Customisation Buggy",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                IconButton(onClick = { viewModel.closeCustomizationMenu() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subpanel split colors vs engine upgrades
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // Left Column: Neon Body Paints
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "PEINTURE CARROSSERIE NEON",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val paints = listOf(
                        "#FF3366" to "Néon Magenta",
                        "#33FF66" to "Néon Crypton Green",
                        "#3366FF" to "Néon Cobalt Blue",
                        "#FFFF33" to "Néon Volt Glow",
                        "#9933FF" to "Néon Ultra Purple",
                        "#FF9933" to "Néon Solar Amber"
                    )

                    paints.forEach { (hex, name) ->
                        val isEquipped = save.vehicleColorHex.equals(hex, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isEquipped) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1B1D2A))
                                .clickable { viewModel.changeVehicleColor(hex) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color(android.graphics.Color.parseColor(hex)), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(name, color = Color.White, fontSize = 14.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            if (isEquipped) {
                                Text("Équipé", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Right Column: Active Parts level info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "PERFORMANCES MECANIQUES",
                        color = Color(0xFFFFB300),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    LevelIndicator("MOTEUR JET", save.vehicleEngineLevel, "Augmente la vitesse maximale et l'accélération.")
                    Spacer(modifier = Modifier.height(12.dp))
                    LevelIndicator("BOUCLIER DE CHOC", save.vehicleBumperLevel, "Résistance accrue aux chocs obstacles & dégâts doublés de bélier.")
                    Spacer(modifier = Modifier.height(12.dp))
                    LevelIndicator("SISMIC-BOOSTER", save.vehicleThrusterLevel, "Permet de bondir par dessus les ravins en terrain accidenté.")

                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A237E).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF3F51B5), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "💡 ASTUCE : Tu peux booster les performances du buggy en récoltant du métal et en ouvrant le menu Forge d'Archibald !",
                            color = Color(0xFF9FA8DA),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LevelIndicator(title: String, level: Int, desc: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Niveau $level", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(desc, color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..5) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(if (i <= level) Color(0xFFFFB300) else Color.DarkGray, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}
