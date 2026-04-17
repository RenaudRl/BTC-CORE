/**
 * Precompiled [btccore.internal-conventions.gradle.kts][Btccore_internal_conventions_gradle] script plugin.
 *
 * @see Btccore_internal_conventions_gradle
 */
public
class Btccore_internalConventionsPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Btccore_internal_conventions_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
