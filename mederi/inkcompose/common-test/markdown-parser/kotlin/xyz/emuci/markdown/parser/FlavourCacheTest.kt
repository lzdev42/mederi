package xyz.emuci.markdown.parser

import xyz.emuci.markdown.parser.block.postprocessors.PostProcessor
import xyz.emuci.markdown.parser.block.starters.BlockStarter
import xyz.emuci.markdown.parser.flavour.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Flavour 配置缓存测试。
 */
class FlavourCacheTest {

    /** 创建一个匿名自定义 Flavour 实例。 */
    private fun createCustomFlavour(): MarkdownFlavour = object : MarkdownFlavour {
        override val blockStarters: List<BlockStarter> = CommonMarkFlavour.blockStarters
        override val postProcessors: List<PostProcessor> = emptyList()
    }

    // ===== 单例方言的全局缓存 =====

    @Test
    fun should_cache_same_singleton_flavour() {
        FlavourCache.clearAll()
        val cache1 = FlavourCache.of(ExtendedFlavour)
        val cache2 = FlavourCache.of(ExtendedFlavour)
        assertSame(cache1, cache2, "Same singleton flavour should return same cache instance")
    }

    @Test
    fun should_cache_different_singleton_flavours_separately() {
        FlavourCache.clearAll()
        val extCache = FlavourCache.of(ExtendedFlavour)
        val cmCache = FlavourCache.of(CommonMarkFlavour)
        val gfmCache = FlavourCache.of(GFMFlavour)

        assertEquals(3, FlavourCache.cacheSize)
        assertTrue(extCache.blockStarters.size > cmCache.blockStarters.size)
        assertTrue(gfmCache.blockStarters.size >= cmCache.blockStarters.size)
    }

    @Test
    fun should_invalidate_singleton_cache() {
        FlavourCache.clearAll()
        val cache1 = FlavourCache.of(ExtendedFlavour)
        assertEquals(1, FlavourCache.cacheSize)
        FlavourCache.invalidate(ExtendedFlavour)
        assertEquals(0, FlavourCache.cacheSize)
        val cache2 = FlavourCache.of(ExtendedFlavour)
        assertNotSame(cache1, cache2, "Should create new cache after invalidation")
    }

    @Test
    fun should_clear_all_singleton_caches() {
        FlavourCache.clearAll()
        FlavourCache.of(ExtendedFlavour)
        FlavourCache.of(CommonMarkFlavour)
        FlavourCache.of(GFMFlavour)
        assertEquals(3, FlavourCache.cacheSize)
        FlavourCache.clearAll()
        assertEquals(0, FlavourCache.cacheSize)
    }

    // ===== 自定义方言不进入全局缓存 =====

    @Test
    fun should_not_cache_custom_flavour_globally() {
        FlavourCache.clearAll()
        val custom = createCustomFlavour()
        FlavourCache.of(custom)
        assertEquals(0, FlavourCache.cacheSize, "Custom flavour should not enter global cache")
    }

    @Test
    fun should_create_new_instance_for_custom_flavour_each_time() {
        FlavourCache.clearAll()
        val custom = createCustomFlavour()
        val cache1 = FlavourCache.of(custom)
        val cache2 = FlavourCache.of(custom)
        assertNotSame(cache1, cache2, "Custom flavour should get new instance each time from of()")
    }

    @Test
    fun should_not_leak_when_creating_many_custom_flavours() {
        FlavourCache.clearAll()
        // 创建大量自定义方言，全局缓存大小不应增长
        repeat(100) {
            FlavourCache.of(createCustomFlavour())
        }
        assertEquals(0, FlavourCache.cacheSize, "Global cache should not grow with custom flavours")
    }

    @Test
    fun should_allow_direct_construction() {
        val custom = createCustomFlavour()
        val cache = FlavourCache(custom)
        assertEquals(CommonMarkFlavour.blockStarters.size, cache.blockStarters.size)
        assertEquals(0, cache.postProcessors.size)
        assertTrue(cache.blockStarterRegistry.isFrozen)
    }

    // ===== 配置快照行为 =====

    @Test
    fun should_cache_block_starters_as_immutable_snapshot() {
        FlavourCache.clearAll()
        val cache = FlavourCache.of(ExtendedFlavour)
        val starters1 = cache.blockStarters
        val starters2 = cache.blockStarters
        assertEquals(starters1, starters2)
        assertEquals(ExtendedFlavour.blockStarters.size, starters1.size)
    }

    @Test
    fun should_cache_post_processors_as_immutable_snapshot() {
        FlavourCache.clearAll()
        val cache = FlavourCache.of(ExtendedFlavour)
        val procs1 = cache.postProcessors
        val procs2 = cache.postProcessors
        assertEquals(procs1, procs2)
        assertEquals(ExtendedFlavour.postProcessors.size, procs1.size)
    }

    @Test
    fun should_provide_cached_block_starter_registry() {
        FlavourCache.clearAll()
        val cache = FlavourCache.of(ExtendedFlavour)
        val registry1 = cache.blockStarterRegistry
        val registry2 = cache.blockStarterRegistry
        assertSame(registry1, registry2, "blockStarterRegistry should be cached within instance")
        assertNotNull(registry1)
    }

    @Test
    fun should_freeze_cached_block_starter_registry() {
        FlavourCache.clearAll()
        val cache = FlavourCache.of(ExtendedFlavour)
        val registry = cache.blockStarterRegistry
        assertTrue(registry.isFrozen, "Cached registry should be frozen")
        assertFailsWith<IllegalStateException>("register on frozen registry should throw") {
            registry.register(CommonMarkFlavour.blockStarters.first())
        }
    }

    @Test
    fun should_return_unfrozen_new_block_starter_registry() {
        FlavourCache.clearAll()
        val cache = FlavourCache.of(ExtendedFlavour)
        val newRegistry = cache.newBlockStarterRegistry()
        assertTrue(!newRegistry.isFrozen, "newBlockStarterRegistry should not be frozen")
        newRegistry.register(CommonMarkFlavour.blockStarters.first())
    }

    @Test
    fun should_provide_new_post_processor_registry_each_time() {
        FlavourCache.clearAll()
        val cache = FlavourCache.of(ExtendedFlavour)
        val reg1 = cache.newPostProcessorRegistry()
        val reg2 = cache.newPostProcessorRegistry()
        assertTrue(reg1 !== reg2, "newPostProcessorRegistry should return new instances")
    }

    // ===== 集成测试 =====

    @Test
    fun should_use_cache_in_parser_without_error() {
        FlavourCache.clearAll()
        val parser1 = MarkdownParser(ExtendedFlavour)
        val parser2 = MarkdownParser(ExtendedFlavour)
        val parser3 = MarkdownParser(ExtendedFlavour)

        val doc1 = parser1.parse("# Hello")
        val doc2 = parser2.parse("## World")
        val doc3 = parser3.parse("### Test")

        assertEquals(1, (doc1.children.first() as xyz.emuci.markdown.parser.ast.Heading).level)
        assertEquals(2, (doc2.children.first() as xyz.emuci.markdown.parser.ast.Heading).level)
        assertEquals(3, (doc3.children.first() as xyz.emuci.markdown.parser.ast.Heading).level)
    }

    @Test
    fun should_use_cache_in_incremental_parse() {
        FlavourCache.clearAll()
        val parser = MarkdownParser(ExtendedFlavour)
        parser.parse("# Hello\n\nWorld")
        parser.insert(offset = 7, text = " Everyone")
        val doc = parser.document
        assertTrue(doc.children.isNotEmpty())
    }

    @Test
    fun should_work_with_custom_flavour_in_parser() {
        FlavourCache.clearAll()
        val custom = createCustomFlavour()
        val parser = MarkdownParser(custom)
        val doc = parser.parse("# Hello")
        assertTrue(doc.children.isNotEmpty())
        // 全局缓存不会增长（custom 不在注册单例集合中）
        assertEquals(0, FlavourCache.cacheSize, "Custom flavour parser should not pollute global cache")
    }
}
