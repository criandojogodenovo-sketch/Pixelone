package com.pixelone.studio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixelone.studio.ui.theme.PixelOneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TESTE INSTRUMENTADO DE UI (Problema 2 — canvas não aparecia ao criar
 * projeto):
 *
 * 1. o ecrã vazio mostra as TRÊS ações principais (Novo projeto,
 *    Abrir recente, Novo spritesheet) — Problema 3;
 * 2. ao criar um projeto pelo diálogo, o canvas fica VISÍVEL DE IMEDIATO e
 *    o ecrã vazio desaparece — sem navegação nem segundo toque;
 * 3. o quadriculado/contorno são desenhados pelo canvas (estado observável
 *    + fit-to-screen provados nos testes do ViewModel).
 */
@RunWith(AndroidJUnit4::class)
class CanvasVisibilityInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun criarProjetoMostraOCanvasImediatamente() {
        composeRule.setContent {
            PixelOneTheme {
                com.pixelone.studio.ui.texture.TextureStudioScreen(onBack = {})
            }
        }

        // Ecrã vazio: as três ações principais visíveis (Problema 3)
        composeRule.onNodeWithTag("action_new_project").assertIsDisplayed()
        composeRule.onNodeWithTag("action_open_recent").assertIsDisplayed()
        composeRule.onNodeWithTag("action_new_spritesheet").assertIsDisplayed()
        composeRule.onNodeWithTag("empty_editor_hint").assertIsDisplayed()

        // Criar projeto → diálogo → Criar (preset 64x64, nome "sprite")
        composeRule.onNodeWithTag("action_new_project").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Tamanhos predefinidos").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Criar").performClick()

        // O canvas APARECE imediatamente e o ecrã vazio DESAPARECE:
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodes(androidx.compose.ui.test.hasTestTag("empty_editor_hint"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithTag("pixelone_canvas").assertIsDisplayed()
    }
}
