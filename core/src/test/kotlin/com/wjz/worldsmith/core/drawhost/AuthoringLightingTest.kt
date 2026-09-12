package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.authoring.AuthoringContext
import com.wjz.worldsmith.core.draw.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthoringLightingTest {
    private fun context()=AuthoringContext(DrawContext(7L,emptyMap(),DrawLimits.DEFAULT)).also {
        it.canvas(Box.of(-8,0,-8,8,15,8)).pen("air").fill(Box.of(-8,0,-8,8,15,8))
    }

    @Test fun `hanging fixtures reach the real varying roof heights without replacing anchors`() {
        val a=context()
        a.canvas().pen("stone").set(-2,8,0).set(2,12,0)
        a.hangingLightFixture("low",Vec3i(-2,3,0),Vec3i(-2,8,0))
        a.hangingLightFixture("high",Vec3i(2,3,0),Vec3i(2,12,0))
        val drawing=a.snapshot().drawing()
        val cells=drawing.voxels().associate {it.position() to it.block().state()}
        for((x,roof)in listOf(-2 to 8,2 to 12)) {
            assertEquals("minecraft:stone",cells.getValue(Vec3i(x,roof,0)).id())
            assertEquals("minecraft:lantern",cells.getValue(Vec3i(x,3,0)).id())
            for(y in 4 until roof)assertEquals("minecraft:iron_chain",cells.getValue(Vec3i(x,y,0)).id())
        }
    }

    @Test fun `absent anchor and obstructed shaft fail before drawing a lamp`() {
        val a=context();val lamp=Vec3i(0,3,0);val roof=Vec3i(0,9,0)
        assertThrows(IllegalArgumentException::class.java){a.hangingLightFixture("missing",lamp,roof)}
        a.canvas().pen("stone").set(0,9,0).set(0,6,0)
        assertThrows(IllegalArgumentException::class.java){a.hangingLightFixture("blocked",lamp,roof)}
        assertTrue(a.canvas().get(lamp).orElseThrow().state().isAir)
    }

    @Test fun `snapshot catches a roof or chain erased after fixture placement`() {
        for(y in listOf(5,9)) {
            val a=context();a.canvas().pen("stone").set(0,9,0)
            a.hangingLightFixture("lamp",Vec3i(0,3,0),Vec3i(0,9,0))
            a.canvas().pen("air").set(0,y,0)
            assertThrows(IllegalArgumentException::class.java){a.snapshot()}
        }
    }

    @Test fun `transformed components keep hanging attachment checks and dark intent explicit`() {
        val child=AuthoringContext(DrawContext(1L,emptyMap(),DrawLimits.DEFAULT))
        child.canvas(Box.of(0,0,0,2,7,2)).pen("air").fill(Box.of(0,0,0,2,7,2))
        child.canvas().pen("stone").set(1,7,1)
        child.hangingLightFixture("lamp",Vec3i(1,2,1),Vec3i(1,7,1))
        val parent=context();val transform=GridTransform.rotateY(1).andThen(GridTransform.translate(4,0,2))
        parent.instance("wing",child.snapshot(),transform)
        parent.snapshot()
        val anchor=transform.apply(Vec3i(1,7,1));parent.canvas().pen("air").set(anchor.x(),anchor.y(),anchor.z())
        assertThrows(IllegalArgumentException::class.java){parent.snapshot()}

        val dark=context().intentionallyDark("A tomb deliberately kept in shadow").snapshot()
        assertThrows(IllegalArgumentException::class.java){context().instance("crypt",dark,GridTransform.IDENTITY)}
        assertEquals("A tomb deliberately kept in shadow",dark.semantics()["lightingIntent"])
        assertFalse(context().snapshot().semantics().containsKey("lightingIntent"))
        assertFalse(context().snapshot().semantics().containsKey("hangingFixtures"))
    }
}
