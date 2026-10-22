package org.staacks.alpharemote.service

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.staacks.alpharemote.R
import org.staacks.alpharemote.SettingsStore
import org.staacks.alpharemote.camera.*
import org.staacks.alpharemote.databinding.AdvancedControlsDrawerBinding
import org.staacks.alpharemote.ui.camera.CameraViewModel

@RunWith(AndroidJUnit4::class)
class IntervalRefocusInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun ready(focus: ReportedBoolean) = CameraStateReady(null, focus, ReportedBoolean(), ReportedBoolean(), emptySet(), emptySet())

    @Test fun freshFocusWaitRejectsPreviousShotAndWaitsForGattQueueToDrain() {
        val wait = CAWaitFor(WaitTarget.FOCUS, requireFreshFocus = true)
        val oldFocus = ReportedBoolean(true)
        assertFalse(wait.isSatisfiedBy(ready(oldFocus), oldFocus, false))
        assertFalse(wait.isSatisfiedBy(ready(ReportedBoolean(false)), oldFocus, false))
        assertFalse(wait.isSatisfiedBy(ready(ReportedBoolean()), oldFocus, false))
        val newFocus = ReportedBoolean(true)
        assertFalse(wait.isSatisfiedBy(ready(newFocus), oldFocus, true))
        assertTrue(wait.isSatisfiedBy(ready(newFocus), oldFocus, false))
    }

    @Test fun existingFocusWaitDoesNotRequireAnotherFocusReport() {
        val focus = ReportedBoolean(true)
        assertTrue(CAWaitFor(WaitTarget.FOCUS).isSatisfiedBy(ready(focus), focus, true))
    }

    @Test fun refocusPreferencePersistsWithoutChangingOtherAdvancedSettings() = runBlocking {
        val store = SettingsStore(context)
        val original = store.getAdvancedControlsState()
        try {
            for (value in listOf(true, false)) {
                store.setAdvancedControlsState(SettingsStore.AdvancedControlsStateOptional(null, null, null, null, null, null, null, value))
                assertEquals(original.copy(intervalRefocus = value), SettingsStore(context).getAdvancedControlsState())
            }
        } finally {
            store.setAdvancedControlsState(SettingsStore.AdvancedControlsStateOptional(null, null, null, null, null, null, null, original.intervalRefocus))
        }
    }

    @Test fun checkboxBindingFollowsIntervalAndManualFocusBracketing() {
        instrumentation.runOnMainSync {
            val vm = CameraViewModel(context.applicationContext as Application)
            val viewModels = ViewModelStore().apply { put("test", vm) }
            val themed = ContextThemeWrapper(context, R.style.Theme_BluetoothRemoteForSonyCameras)
            val binding = AdvancedControlsDrawerBinding.inflate(LayoutInflater.from(themed))
            try {
                binding.viewModel = vm
                vm.intervalToggle.set(false)
                binding.executePendingBindings()
                assertFalse(binding.intervalRefocus.isEnabled)
                vm.intervalToggle.set(true)
                vm.focusBracketingToggle.set(false)
                binding.executePendingBindings()
                assertTrue(binding.intervalRefocus.isEnabled)
                binding.intervalRefocus.isChecked = true
                assertEquals(true, vm.intervalRefocus.get())
                vm.focusBracketingToggle.set(true)
                binding.executePendingBindings()
                assertFalse(binding.intervalRefocus.isEnabled)
                assertTrue(binding.intervalRefocus.isChecked)
                vm.focusBracketingToggle.set(false)
                binding.executePendingBindings()
                assertTrue(binding.intervalRefocus.isEnabled)
            } finally {
                binding.unbind()
                viewModels.clear()
            }
        }
    }
}
