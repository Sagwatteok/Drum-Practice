package io.github.sagwatteok.drumpractice.feature.diagnostics

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sagwatteok.drumpractice.core.midi.Drum
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockMappingTest {
    @Test fun displacedAndRemovedNotesDoNotProduceMockHits() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val mappings = File(app.cacheDir, "mock-mapping-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { mappings }
        val models = ViewModelStore()
        lateinit var practice: PracticeViewModel
        instrumentation.runOnMainSync {
            val factory = viewModelFactory { initializer { PracticeViewModel(app, dataStore) } }
            practice = ViewModelProvider(models, factory)[PracticeViewModel::class.java]
        }
        try {
            runBlocking { withTimeout(5_000) { practice.practice.first { it.mappingReady } } }
            instrumentation.runOnMainSync { practice.saveMapping(Drum.KICK, 38) }
            val updated = runBlocking {
                withTimeout(5_000) { practice.practice.first { it.mapping[Drum.KICK] == 38 } }
            }
            assertNull(updated.mapping[Drum.SNARE])
            instrumentation.runOnMainSync {
                practice.mockHit(Drum.SNARE)
                assertEquals(0L, practice.practice.value.hitCount)
                practice.mockHit(Drum.KICK)
                assertEquals(1L, practice.practice.value.drumCounts[Drum.KICK])
                practice.saveMapping(Drum.KICK, null)
            }
            runBlocking { withTimeout(5_000) { practice.practice.first { Drum.KICK !in it.mapping } } }
            instrumentation.runOnMainSync {
                practice.mockHit(Drum.KICK)
                assertEquals(1L, practice.practice.value.hitCount)
                practice.setMockConnected(false)
                practice.mockHit(Drum.RIDE)
                assertEquals(1L, practice.practice.value.hitCount)
                assertFalse(practice.practice.value.mockConnected)
            }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            scope.cancel()
            mappings.delete()
        }
    }
}
