package com.pixelone.studio.studio3d

/**
 * Barramento de partilha de textura entre o 3D Texture Studio e o Texture
 * Studio (2D) — singleton de processo (os dois ecrãs são rotas distintas do
 * Navigation Compose, com ViewModels próprios).
 *
 * FLUXOS (requisito Fase 3: "Pintar esta textura" e "o 3D atualiza quando a
 * textura muda, sem reiniciar o projeto"):
 *
 *  1. 3D → 2D ("Pintar esta textura"): o 3D publica [publishTo2D], navega
 *     para o Texture Studio; o ecrã 2D oferece/adopta a textura ao entrar.
 *  2. 2D → 3D (atualização ao voltar): o Texture Studio publica o estado
 *     atual do documento quando sai (onDispose/ON_STOP); o 3D adota com
 *     [consumeFrom2D] quando a rota volta a ficar visível — SEM reiniciar
 *     o projeto nem o modelo 3D.
 *
 * Os dados são cópias (IntArray) — nunca referências vivas partilhadas entre
 * ViewModels com executores distintos (disciplina de threading do projeto).
 */
object StudioSharedBus {

    /** Cópia da textura + guias UV opcionais + contador de versão. */
    class TextureHandoff(
        val name: String,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        /** Segmentos UV (u0,v0,u1,v1)* — guia visual opcional no 2D. */
        val uvSegments: FloatArray?,
        val version: Long
    )

    @Volatile
    private var pendingFor2D: TextureHandoff? = null

    @Volatile
    private var latestFrom2D: TextureHandoff? = null

    @Volatile
    private var lastConsumedFrom2DVersion: Long = Long.MIN_VALUE

    /** 3D → 2D: publica a textura a "Pintar". */
    fun publishTo2D(handoff: TextureHandoff) {
        pendingFor2D = handoff
    }

    /** 2D: consome a oferta pendente (uma única vez). */
    fun consumeFor2D(): TextureHandoff? {
        val value = pendingFor2D
        pendingFor2D = null
        return value
    }

    /** 2D → 3D: publica a textura atual (chamado ao sair do Texture Studio). */
    fun publishFrom2D(handoff: TextureHandoff) {
        latestFrom2D = handoff
    }

    /**
     * 3D: devolve a versão mais recente publicada pelo 2D que ainda não foi
     * consumida (null se nada novo).
     */
    fun consumeFrom2D(): TextureHandoff? {
        val value = latestFrom2D ?: return null
        if (value.version == lastConsumedFrom2DVersion) return null
        lastConsumedFrom2DVersion = value.version
        return value
    }

    /** Limpa tudo (testes). */
    fun clear() {
        pendingFor2D = null
        latestFrom2D = null
        lastConsumedFrom2DVersion = Long.MIN_VALUE
    }
}
