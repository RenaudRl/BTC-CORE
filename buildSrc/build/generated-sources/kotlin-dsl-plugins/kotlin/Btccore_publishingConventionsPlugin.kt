/**
 * Precompiled [btccore.publishing-conventions.gradle.kts][Btccore_publishing_conventions_gradle] script plugin.
 *
 * @see Btccore_publishing_conventions_gradle
 */
public
class Btccore_publishingConventionsPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Btccore_publishing_conventions_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
