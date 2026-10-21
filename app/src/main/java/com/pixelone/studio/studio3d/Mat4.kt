package com.pixelone.studio.studio3d

/**
 * Matemática de matrizes 4x4 do 3D Texture Studio — Kotlin puro, SEM APIs
 * Android, para ser totalmente testável em JVM (o resto do projeto testa o
 * motor 2D da mesma forma).
 *
 * CONVENÇÃO: column-major, igual ao OpenGL/android.opengl.Matrix — um FloatArray
 * de 16 em que m[col*4 + row]. Todas as funções são PURAS (não mutam argumentos)
 * e escrevem o resultado no array `out` fornecido (zero alocações por frame).
 *
 * Só existe o necessário para a câmara orbital e o baking de transformações
 * de nós glTF: identidade, multiplicação, perspectiva, lookAt, translação/
 * escala/rotação TRS (com quaternião), transformar ponto e normalizar.
 */
object Mat4 {

    const val M00 = 0; const val M01 = 4; const val M02 = 8;  const val M03 = 12
    const val M10 = 1; const val M11 = 5; const val M12 = 9;  const val M13 = 13
    const val M20 = 2; const val M21 = 6; const val M22 = 10; const val M23 = 14
    const val M30 = 3; const val M31 = 7; const val M32 = 11; const val M33 = 15

    fun identity(out: FloatArray): FloatArray {
        java.util.Arrays.fill(out, 0f)
        out[M00] = 1f; out[M11] = 1f; out[M22] = 1f; out[M33] = 1f
        return out
    }

    /** out = a * b (ordem OpenGL: aplica b primeiro). a, b e out podem ser o mesmo array? NÃO — out tem de ser distinto. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        for (col in 0..3) {
            val b0 = b[col * 4]; val b1 = b[col * 4 + 1]; val b2 = b[col * 4 + 2]; val b3 = b[col * 4 + 3]
            out[col * 4]     = a[M00] * b0 + a[M01] * b1 + a[M02] * b2 + a[M03] * b3
            out[col * 4 + 1] = a[M10] * b0 + a[M11] * b1 + a[M12] * b2 + a[M13] * b3
            out[col * 4 + 2] = a[M20] * b0 + a[M21] * b1 + a[M22] * b2 + a[M23] * b3
            out[col * 4 + 3] = a[M30] * b0 + a[M31] * b1 + a[M32] * b2 + a[M33] * b3
        }
        return out
    }

    /**
     * Projeção perspetiva (convencões OpenGL, clip -1..1, olhando para -Z).
     * fovyRadians: abertura vertical; aspect = largura/altura.
     */
    fun perspective(
        out: FloatArray,
        fovyRadians: Float,
        aspect: Float,
        near: Float,
        far: Float
    ): FloatArray {
        java.util.Arrays.fill(out, 0f)
        val f = 1f / Math.tan((fovyRadians / 2f).toDouble()).toFloat()
        require(aspect > 0f && near > 0f && far > near) { "Parâmetros de projeção inválidos" }
        out[M00] = f / aspect
        out[M11] = f
        out[M22] = (far + near) / (near - far)
        out[M32] = -1f
        out[M23] = (2f * far * near) / (near - far)
        return out
    }

    /**
     * Vista lookAt (convenção destral: a câmara olha de eye para center).
     * up é o vetor "para cima" (normalizado internamente).
     */
    fun lookAt(
        out: FloatArray,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float
    ): FloatArray {
        // z = normalize(eye - center) (aponta da cena para a câmara)
        var zx = eyeX - centerX; var zy = eyeY - centerY; var zz = eyeZ - centerZ
        val zLen = Math.sqrt((zx * zx + zy * zy + zz * zz).toDouble()).toFloat()
        require(zLen > 1e-8f) { "Eye e center coincidem na câmara" }
        zx /= zLen; zy /= zLen; zz /= zLen
        // x = normalize(up × z)
        var xx = upY * zz - upZ * zy
        var xy = upZ * zx - upX * zz
        var xz = upX * zy - upY * zx
        val xLen = Math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat()
        require(xLen > 1e-8f) { "Up paralelo à direção da câmara" }
        xx /= xLen; xy /= xLen; xz /= xLen
        // y = z × x
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx

        out[M00] = xx; out[M01] = yx; out[M02] = zx; out[M03] = -(xx * eyeX + xy * eyeY + xz * eyeZ)
        out[M10] = xy; out[M11] = yy; out[M12] = zy; out[M13] = -(yx * eyeX + yy * eyeY + yz * eyeZ)
        out[M20] = xz; out[M21] = yz; out[M22] = zz; out[M23] = -(zx * eyeX + zy * eyeY + zz * eyeZ)
        out[M30] = 0f; out[M31] = 0f; out[M32] = 0f; out[M33] = 1f
        return out
    }

    /** out = TRS de translação t, rotação por quaternião (x,y,z,w), escala uniforme s. */
    fun trs(
        out: FloatArray,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        sx: Float, sy: Float, sz: Float
    ): FloatArray {
        // Matriz de rotação do quaternião (fórmula padrão, quaternião unitário).
        val x2 = qx + qx; val y2 = qy + qy; val z2 = qz + qz
        val xx = qx * x2; val xy = qx * y2; val xz = qx * z2
        val yy = qy * y2; val yz = qy * z2; val zz = qz * z2
        val wx = qw * x2; val wy = qw * y2; val wz = qw * z2

        // M = T * R * S (column-major): m[col*4+row]; a escala s_j multiplica a
        // COLUNA j (m[0..2]→sx, m[4..6]→sy, m[8..10]→sz) e a rotação entra SEM
        // transposta (R[linha][coluna] → m[col*4+linha]).
        out[M00] = (1f - (yy + zz)) * sx; out[M01] = (xy - wz) * sy;        out[M02] = (xz + wy) * sz;        out[M03] = tx
        out[M10] = (xy + wz) * sx;        out[M11] = (1f - (xx + zz)) * sy; out[M12] = (yz - wx) * sz;        out[M13] = ty
        out[M20] = (xz - wy) * sx;        out[M21] = (yz + wx) * sy;       out[M22] = (1f - (xx + yy)) * sz; out[M23] = tz
        out[M30] = 0f;                    out[M31] = 0f;                   out[M32] = 0f;                   out[M33] = 1f
        return out
    }

    /** Transforma o ponto (x, y, z) pela matriz (w implícito = 1). Devolve FloatArray(3). */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val rx = m[M00] * x + m[M01] * y + m[M02] * z + m[M03]
        val ry = m[M10] * x + m[M11] * y + m[M12] * z + m[M13]
        val rz = m[M20] * x + m[M21] * y + m[M22] * z + m[M23]
        return floatArrayOf(rx, ry, rz)
    }

    /** Normaliza um FloatArray(3) in-place; devolve o próprio array. */
    fun normalize3(v: FloatArray): FloatArray {
        val len = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        require(len > 1e-12f) { "Vetor nulo não pode ser normalizado" }
        v[0] /= len; v[1] /= len; v[2] /= len
        return v
    }
}
