package com.agentpjt.shop.shop

/**
 * 모델이 도구 없이 한 말을 화면·TTS 에 맞게 다듬는다. 말뜻은 건드리지 않고 형식이 확실한 것만 지운다:
 * 우리가 붙인 상태 블록 줄([화면] …), 마크다운 기호, 상품 id. 실기기에서 모델이 이것들을 그대로 써서
 * TTS 가 "별표 별표"·"아이디 피 공이…"를 읽었다(eval1.log).
 */
object SpeechText {

    private val TAG_LINE = Regex("""^\s*\[[^\]\n]{1,12}].*$""", RegexOption.MULTILINE) // stateBlock 줄을 따라 쓴 것
    private val ID_PAREN = Regex("""\(\s*(?:ID\s*[:：]\s*)?p\d{5}\s*\)""", RegexOption.IGNORE_CASE)
    private val ID = Regex("""p\d{5}""")
    private val HEADING = Regex("""^\s*#+\s*""", RegexOption.MULTILINE)
    private val LIST_MARK = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""", RegexOption.MULTILINE)
    private val EMPHASIS = Regex("""\*+|_{2,}|`+""")
    private val DASH = Regex("""\s+[-–—]\s+""")
    private val EMPTY_PAREN = Regex("""\(\s*\)""")
    private val SPACES = Regex("""\s+""")
    private val SENTENCE_END = Regex("""(?<=[.?!])\s+""")

    /** 화면 말풍선에 보일 전문. */
    fun clean(text: String): String = text
        .replace(TAG_LINE, "")
        .replace(ID_PAREN, "")
        .replace(ID, "")
        .replace(HEADING, "")
        .replace(LIST_MARK, "")
        .replace(EMPHASIS, "")
        .replace(DASH, ", ")
        .replace(EMPTY_PAREN, "")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        .replace(SPACES, " ")
        .trim()

    /** 소리로 읽을 앞부분. 어르신이 한 번에 듣기 좋은 길이에서 끊고, 나머지는 말풍선으로 본다. */
    fun spoken(text: String, maxSentences: Int = 2, maxChars: Int = 120): String {
        val sentences = clean(text).split(SENTENCE_END).filter { it.isNotBlank() }
        val out = StringBuilder()
        for (s in sentences.take(maxSentences)) {
            if (out.isNotEmpty() && out.length + 1 + s.length > maxChars) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(s)
        }
        if (out.length <= maxChars) return out.toString()
        val cut = out.lastIndexOf(" ", maxChars).takeIf { it > 0 } ?: maxChars
        return out.substring(0, cut)
    }
}
