package dev.sweep.assistant.views

interface Darkenable {
    fun applyDarkening()

    fun revertDarkening()
}

interface DarkenableContainer : Darkenable {
    val darkenableChildren: List<Darkenable>

    fun revalidate()

    fun repaint()

    override fun applyDarkening() {
        darkenableChildren.forEach { it.applyDarkening() }
        revalidate()
        repaint()
    }

    override fun revertDarkening() {
        darkenableChildren.forEach { it.revertDarkening() }
        revalidate()
        repaint()
    }
}
