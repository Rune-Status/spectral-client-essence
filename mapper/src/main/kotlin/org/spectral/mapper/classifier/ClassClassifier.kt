package org.spectral.mapper.classifier

import org.objectweb.asm.Opcodes.*
import org.spectral.asm.Class
import org.spectral.asm.Field
import org.spectral.asm.Method
import org.spectral.asm.util.newIdentityHashSet
import org.spectral.mapper.util.CompareUtil
import kotlin.math.max
import kotlin.math.pow

/**
 * Responsible for classifying [Class] objects.
 */
object ClassClassifier : Classifier<Class>() {

    /**
     * Register the classifier checks.
     */
    override fun init() {
        register(classTypeCheck, 20)
        register(hierarchyDepth, 1)
        register(parentClass, 4)
        register(childClasses, 3)
        register(interfaces, 3)
        register(implementers, 2)
        register(methodCount, 3)
        register(fieldCount, 3)
        register(hierarchySiblings, 2)
        register(similarMethods, 10)
        register(outReferences, 6)
        register(inReferences, 6)
        register(methodOutReferences, 6, ClassifierLevel.SECONDARY, ClassifierLevel.EXTRA, ClassifierLevel.FINAL)
        register(methodInReferences, 6, ClassifierLevel.SECONDARY, ClassifierLevel.EXTRA, ClassifierLevel.FINAL)
        register(fieldReadReferences, 5, ClassifierLevel.SECONDARY, ClassifierLevel.EXTRA, ClassifierLevel.FINAL)
        register(fieldWriteReferences, 5, ClassifierLevel.SECONDARY, ClassifierLevel.EXTRA, ClassifierLevel.FINAL)
    }

    /////////////////////////////////////////////
    // CLASSIFIER CHECKS
    /////////////////////////////////////////////

    private val classTypeCheck = classifier("class type check") { a, b ->
        val mask = ACC_ENUM or ACC_INTERFACE or ACC_ANNOTATION or ACC_ABSTRACT
        val resultA = a.access and mask
        val resultB = b.access and mask

        return@classifier (1 - Integer.bitCount(resultA pow resultB) / 4).toDouble()
    }

    private val hierarchyDepth = classifier("hierarchy depth") { a, b ->
        var countA = 0
        var countB = 0

        var clsA: Class? = a
        var clsB: Class? = b

        while(clsA?.parent != null) {
            clsA = clsA.parent
            countA++
        }

        while(clsB?.parent != null) {
            clsB = clsB.parent
            countB++
        }

        return@classifier CompareUtil.compareCounts(countA, countB)
    }

    private val hierarchySiblings = classifier("hierarchy siblings") { a, b ->
        return@classifier CompareUtil.compareCounts(a.parent?.children?.size ?: 0, b.parent?.children?.size ?: 0)
    }

    private val parentClass = classifier("parent class") { a, b ->
        if(a.parent == null && b.parent == null) return@classifier 1.0
        if(a.parent == null || b.parent == null) return@classifier 0.0

        return@classifier if(CompareUtil.isPotentiallyEqual(a.parent!!, b.parent!!)) 1.0 else 0.0
    }

    private val childClasses = classifier("child classes") { a, b ->
        return@classifier CompareUtil.compareClassSets(a.children, b.children)
    }

    private val interfaces = classifier("interfaces") { a, b ->
        return@classifier CompareUtil.compareClassSets(a.interfaces, b.interfaces)
    }

    private val implementers = classifier("implementers") { a, b ->
        return@classifier CompareUtil.compareClassSets(a.implementers, b.implementers)
    }

    private val methodCount = classifier("method count") { a, b ->
        return@classifier CompareUtil.compareCounts(a.methods.size, b.methods.size)
    }

    private val fieldCount = classifier("field count") { a, b ->
        return@classifier CompareUtil.compareCounts(a.fields.size, b.fields.size)
    }

    private val similarMethods = classifier("similar methods") { a, b ->
        if(a.methods.isEmpty() && b.methods.isEmpty()) return@classifier 1.0
        if(a.methods.isEmpty() || b.methods.isEmpty()) return@classifier 0.0

        val methodsB = newIdentityHashSet(b.methods.values.toList())
        var totalScore = 0.0
        var bestMatch: Method? = null
        var bestScore = 0.0

        for(methodA in a.methods.values) {
            methodBLoop@ for(methodB in methodsB) {
                if(!CompareUtil.isPotentiallyEqual(methodA, methodB)) continue
                if(!CompareUtil.isPotentiallyEqual(methodA.returnTypeClass, methodB.returnTypeClass)) continue

                val argsA = methodA.arguments
                val argsB = methodB.arguments

                if(argsA.size != argsB.size) continue

                for(i in argsA.indices) {
                    val argA = argsA[i].typeClass
                    val argB = argsB[i].typeClass

                    if(!CompareUtil.isPotentiallyEqual(argA, argB)) {
                        continue@methodBLoop
                    }
                }

                val score = if(!methodA.real || !methodB.real) {
                    if(!methodA.real && !methodB.real) 1.0 else 0.0
                } else {
                    CompareUtil.compareCounts(methodA.instructions.size(), methodB.instructions.size())
                }

                if(score > bestScore) {
                    bestScore = score
                    bestMatch = methodB
                }
            }

            if(bestMatch != null) {
                totalScore += bestScore
                methodsB.remove(bestMatch)
            }
        }

        return@classifier totalScore / max(a.methods.size, b.methods.size)
    }

    private val outReferences = classifier("out references") { a, b ->
        val refsA = a.getOutReferences()
        val refsB = b.getOutReferences()

        return@classifier CompareUtil.compareClassSets(refsA, refsB)
    }

    private val inReferences = classifier("in references") { a, b ->
        val refsA = a.getInReferences()
        val refsB = b.getInReferences()

        return@classifier CompareUtil.compareClassSets(refsA, refsB)
    }

    private val methodOutReferences = classifier("method out references") { a, b ->
        val refsA = a.getMethodOutReferences()
        val refsB = b.getMethodOutReferences()

        return@classifier CompareUtil.compareMethodSets(refsA, refsB)
    }

    private val methodInReferences = classifier("method in references") { a, b ->
        val refsA = a.getMethodInReferences()
        val refsB = b.getMethodInReferences()

        return@classifier CompareUtil.compareMethodSets(refsA, refsB)
    }

    private val fieldReadReferences = classifier("field read references") { a, b ->
        val refsA = a.getFieldReadReferences()
        val refsB = b.getFieldReadReferences()

        return@classifier CompareUtil.compareFieldSets(refsA, refsB)
    }

    private val fieldWriteReferences = classifier("field write references") { a, b ->
        val refsA = a.getFieldWriteReferences()
        val refsB = b.getFieldWriteReferences()

        return@classifier CompareUtil.compareFieldSets(refsA, refsB)
    }

    /////////////////////////////////////////////
    // HELPER METHODS
    /////////////////////////////////////////////

    private fun Class.getOutReferences(): Set<Class> {
        val ret = newIdentityHashSet<Class>()

        this.methods.values.forEach { m ->
            ret.addAll(m.classRefs)
        }

        this.fields.values.forEach { f ->
            ret.add(f.typeClass)
        }

        return ret
    }

    private fun Class.getInReferences(): Set<Class> {
        val ret = newIdentityHashSet<Class>()

        this.methodTypeRefs.forEach { ret.add(it.owner) }
        this.fieldTypeRefs.forEach { ret.add(it.owner) }

        return ret
    }

    private fun Class.getMethodOutReferences(): Set<Method> {
        val ret = newIdentityHashSet<Method>()

        this.methods.values.forEach {
            ret.addAll(it.refsOut)
        }

        return ret
    }

    private fun Class.getMethodInReferences(): Set<Method> {
        val ret = newIdentityHashSet<Method>()

        this.methods.values.forEach { ret.addAll(it.refsIn) }

        return ret
    }

    private fun Class.getFieldReadReferences(): Set<Field> {
        val ret = newIdentityHashSet<Field>()

        this.methods.values.forEach { ret.addAll(it.fieldReadRefs) }

        return ret
    }

    private fun Class.getFieldWriteReferences(): Set<Field> {
        val ret = newIdentityHashSet<Field>()

        this.methods.values.forEach { ret.addAll(it.fieldWriteRefs) }

        return ret
    }

    private infix fun Int.pow(value: Int): Int {
        return this.toDouble().pow(value.toDouble()).toInt()
    }
}