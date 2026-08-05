package org.lattice.ui.diagnostics

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.lattice.mesh.FakeMeshController
import org.lattice.mesh.MeshController
import org.lattice.mesh.MeshMetrics

/**
 * Demonstrates finding #15's payoff: with the mesh behind [MeshController], a ViewModel is now testable
 * against the shared [FakeMeshController] fixture instead of the concrete, un-constructable `MeshManager`.
 * Verifies the Diagnostics actions route to the controller.
 */
class DiagnosticsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rescanAndRestartRouteToTheController() =
        runTest {
            val controller = FakeMeshController()
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = mockk(relaxed = true),
                    metrics = MeshMetrics(),
                )

            vm.rescan()
            vm.restartMesh()

            assertEquals(1, controller.healCount)
            assertEquals(1, controller.restartCount)
        }
}
