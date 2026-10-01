package io.legado.app.help.readaloud.segment

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechRoleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V5FinalSpeechRegressionTest {

    @Test
    fun `short attributed quote is dialogue`() {
        val text = "张三低声说：“嗯”"
        val result = RuleBasedSpeechSegmenter.segment(
            listOf(CanonicalSpeechParagraph(0, text, 0))
        )
        assertTrue(result.any { it.text == "“嗯”" && it.roleType == SpeechRoleType.Character })
    }

    @Test
    fun `short quote followed by attribution is dialogue`() {
        val text = "“走。”李四沉声道。"
        val result = RuleBasedSpeechSegmenter.segment(
            listOf(CanonicalSpeechParagraph(0, text, 0))
        )
        assertTrue(result.any { it.text == "“走。”" && it.roleType == SpeechRoleType.Character })
    }

    @Test
    fun `short quoted term stays narration`() {
        val text = "此物名为“青锋”，并非人名。"
        val result = RuleBasedSpeechSegmenter.segment(
            listOf(CanonicalSpeechParagraph(0, text, 0))
        )
        assertEquals(listOf(SpeechRoleType.Narrator), result.map { it.roleType }.distinct())
    }

    @Test
    fun `quoted thought stays thought`() {
        val text = "她心想：“不能让他们发现。”"
        val result = RuleBasedSpeechSegmenter.segment(
            listOf(CanonicalSpeechParagraph(0, text, 0))
        )
        assertTrue(result.any { it.roleType == SpeechRoleType.Thought })
    }

    @Test
    fun `punctuation run remains attached`() {
        val text = "他惊呼：“什么？！”随后退了一步。"
        val atoms = AiSpeechAtomizer.atomize(CanonicalSpeechParagraph(0, text, 0))
        assertEquals(text, atoms.joinToString("") { it.text })
        assertFalse(atoms.any { it.text == "！" || it.text == "？" })
    }

    @Test
    fun `ellipsis is not emitted as isolated noise`() {
        val text = "“我……我不知道。”她低下头。"
        val atoms = AiSpeechAtomizer.atomize(CanonicalSpeechParagraph(0, text, 0))
        assertEquals(text, atoms.joinToString("") { it.text })
        assertFalse(atoms.any { it.text == "…" })
    }

    @Test
    fun `semicolon does not force a hard atom break`() {
        val text = "他停了一下；随后继续说道：“走吧。”"
        val atoms = AiSpeechAtomizer.atomize(CanonicalSpeechParagraph(0, text, 0))
        assertEquals(text, atoms.joinToString("") { it.text })
        assertFalse(atoms.any { it.text == "；" || it.text == ";" })
    }

    @Test
    fun `english apostrophe inside word stays intact`() {
        val text = "Dean said: \"Don't move!\""
        val atoms = AiSpeechAtomizer.atomize(CanonicalSpeechParagraph(0, text, 0))
        assertEquals(text, atoms.joinToString("") { it.text })
        assertFalse(atoms.any { it.text == "'" })
    }
}
