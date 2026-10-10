package com.sohailsquad.animalhealthbridge.ml.translation

import android.content.Context
import java.io.File

object TranslatorConfig {
    val languageTags = mapOf("hi" to "hin_Deva", "kn" to "kan_Knda", "ta" to "tam_Taml",
        "te" to "tel_Telu", "ml" to "mal_Mlym", "mr" to "mar_Deva", "bn" to "ben_Beng",
        "gu" to "guj_Gujr", "pa" to "pan_Guru", "or" to "ory_Orya", "as" to "asm_Beng",
        "ur" to "urd_Arab", "en" to "eng_Latn")
    fun directory(context: Context, source: String, target: String = "en"): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models/indictrans2/$source-$target")
}
