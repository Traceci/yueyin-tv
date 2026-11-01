package com.yueyin.tv.core.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 静态体检的回归测试。
 *
 * 背景：真实音源基本都过了 JS 混淆器，脚本里不会有字面量 `lx.on(` / `lx.send(`。
 * 之前按字面量卡，导入时会把能用的脚本全部拒掉（"不像 LX 音源脚本"），
 * 所以这里把"混淆脚本必须放行"固定成用例。
 */
class SourceScriptValidatorTest {

    @Test
    fun `放行混淆过的真实音源`() {
        val obfuscated = """
            /*
             * @name fish_music
             * @version 1.9.0
             */
            (function(_0x1f0227,_0x1bdaf9){const _0x16cda7={_0x185065:0x4b2};
            function _0x4d3e(_0x2a){return _0x16cda7[_0x2a];}})();
        """.trimIndent()
        assertNull(SourceScriptValidator.validate(obfuscated))
    }

    @Test
    fun `只有 lx send 没有 lx on 也放行`() {
        assertNull(SourceScriptValidator.validate("function init(){ lx.send('inited', {}) }"))
    }

    @Test
    fun `空文件被拒`() {
        assertNotNull(SourceScriptValidator.validate("   \n  "))
    }

    @Test
    fun `网页被拒`() {
        assertNotNull(SourceScriptValidator.validate("<!DOCTYPE html><html><body>hi</body></html>"))
    }

    @Test
    fun `访问本地文件被拒`() {
        val bad = "const fs = require('fs'); function x(){}"
        assertEquals("脚本会读写本地文件，已拒绝", SourceScriptValidator.validate(bad))
    }
}
