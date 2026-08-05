package com.wlav.stlauncher.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class AndroidRuntimeBackend(
    context: Context,
    scope: CoroutineScope,
    private val portAllocator: PortAllocator = LoopbackPortAllocator(),
    private val installer: RuntimeInstaller =
        RuntimeBundleInstaller(storageSpaceProvider = AndroidStorageSpaceProvider()),
    private val migrator: LegacyDataMigrator = JournaledLegacyDataMigrator(),
    injectedProcessLauncher: NodeProcessLauncher? = null,
    private val healthProbe: HealthProbe = LauncherPluginHealthProbe(),
    private val versionRegistry: RuntimeVersionRegistry = RuntimeVersionRegistry(),
) : RuntimeBackend {
    private val appContext = context.applicationContext
    private val filesRoot = appContext.filesDir.toPath()
    private val noBackupRoot = appContext.noBackupFilesDir.toPath()
    private val runtimeRoot = noBackupRoot.resolve("runtime")
    private val dataRoot = filesRoot.resolve("user-data")
    private val configFile = filesRoot.resolve("config/config.yaml")
    private val legacyRoot = filesRoot.resolve("sillytavern/SillyTavern")
    private val legacyData = legacyRoot.resolve("data")
    private val migrationJournal = noBackupRoot.resolve("migration/legacy-data.journal")
    private val operationRoot = noBackupRoot.resolve("operations")
    private val importTransactions =
        ImportTransactionManager(
            operationsRoot = operationRoot,
            liveData = dataRoot,
            journal = noBackupRoot.resolve("import.journal"),
        )
    private val logStore = RotatingLogStore(noBackupRoot.resolve("logs").toFile())
    private val processLauncher =
        injectedProcessLauncher ?: LinkerNodeProcessLauncher(appContext, scope, logStore)
    private val manifest: RuntimeManifest by lazy {
        appContext.assets.open(RUNTIME_MANIFEST_ASSET).bufferedReader().use {
            RuntimeManifest.parse(it.readText())
        }
    }

    @Volatile
    private var installedRuntime: File? = null

    @Volatile
    private var runtimeActivation: RuntimeActivation? = null

    @Volatile
    private var importAwaitingHealth: ImportTransactionManager.Transaction? = null

    override suspend fun prepare(reporter: PreparationReporter) =
        withContext(Dispatchers.IO) {
            reporter.report(PreparationStage.RECOVERING, null)
            runtimeRoot.createDirectories()
            operationRoot.createDirectories()
            installer.recover(runtimeRoot)
            if (importAwaitingHealth == null) {
                importTransactions.recover()
            }

            migrator.recover(dataRoot, migrationJournal)

            reporter.report(PreparationStage.INSTALLING_RUNTIME, 0)
            val candidateRuntime =
                appContext.assets.open(RUNTIME_BUNDLE_ASSET).use { bundle ->
                    installer.install(bundle, manifest, runtimeRoot) { progress ->
                        if (progress % 2 == 0) {
                            reporter.report(PreparationStage.INSTALLING_RUNTIME, progress)
                        }
                    }
                }
            check(candidateRuntime.fileName.toString() == manifest.versionHash) {
                "Runtime installer returned an unexpected version"
            }
            runtimeActivation =
                versionRegistry.select(runtimeRoot, manifest.versionHash)
            val selectedRuntime =
                runtimeActivation?.runtimePath
                    ?: throw IllegalStateException("Runtime activation was not selected")
            installedRuntime = selectedRuntime.toFile()

            reporter.report(PreparationStage.MIGRATING_DATA, 0)
            val migration =
                migrator.migrate(
                    legacyData = legacyData,
                    legacyConfig = legacyRoot.resolve("config.yaml"),
                    targetData = dataRoot,
                    targetConfig = configFile,
                    journal = migrationJournal,
                ) { progress ->
                    reporter.report(PreparationStage.MIGRATING_DATA, progress)
                }
            Log.i(TAG, "Legacy migration result: $migration")

            reporter.report(PreparationStage.PREPARING_CONFIGURATION, null)
            Files.createDirectories(dataRoot)
            Files.createDirectories(configFile.parent)
            if (!configFile.exists()) {
                val defaultConfig = selectedRuntime.resolve("app/default/config.yaml")
                val fallbackConfig = selectedRuntime.resolve("app/config.yaml")
                val source =
                    when {
                        defaultConfig.exists() -> defaultConfig
                        fallbackConfig.exists() -> fallbackConfig
                        else -> throw IOException("Bundled SillyTavern config template is missing")
                    }
                Files.copy(source, configFile, StandardCopyOption.COPY_ATTRIBUTES)
            }

            if (migration is MigrationResult.Migrated) {
                noBackupRoot
                    .resolve("migration/pending-health-confirmation")
                    .apply { parent.createDirectories() }
                    .toFile()
                    .writeText(manifest.runtimeId)
            }
        }

    override suspend fun launch(attempt: Int): ManagedRuntime =
        try {
            processLauncher.launch(createLaunchRequest(attempt))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val fallback = switchToPreviousRuntime() ?: throw error
            Log.w(
                TAG,
                "Candidate runtime failed before health check; falling back to ${fallback.runtimeId}",
                error,
            )
            processLauncher.launch(createLaunchRequest(attempt))
        }

    override suspend fun awaitReady(
        runtime: ManagedRuntime,
        timeoutMillis: Long,
    ): RuntimeEndpoint {
        val endpoint =
            try {
                healthProbe.awaitReady(runtime, timeoutMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val fallback = switchToPreviousRuntime() ?: throw error
                throw RuntimeExceptionWithCode(
                    code = "CANDIDATE_RUNTIME_FAILED",
                    message =
                        "Runtime update failed its health check; " +
                            "restoring ${fallback.runtimeId}",
                    cause = error,
                )
            }
        runtimeActivation
            ?.takeIf(RuntimeActivation::isCandidate)
            ?.let { activation ->
                try {
                    versionRegistry.commit(runtimeRoot, activation)
                } catch (error: Throwable) {
                    val fallback = switchToPreviousRuntime()
                    throw RuntimeExceptionWithCode(
                        code = "RUNTIME_ACTIVATION_FAILED",
                        message =
                            fallback?.let {
                                "Unable to commit the runtime update; restored ${it.runtimeId}"
                            } ?: "Unable to commit the runtime update",
                        cause = error,
                    )
                }
                runtimeActivation = activation.copy(isCandidate = false)
            }
        confirmHealthyMigration()
        return endpoint
    }

    private fun createLaunchRequest(attempt: Int): NodeLaunchRequest {
        val runtime =
            installedRuntime
                ?: throw IllegalStateException("Runtime has not been prepared")
        return NodeLaunchRequest(
            runtimeDirectory = runtime,
            configFile = configFile,
            dataRoot = dataRoot,
            operationsRoot = operationRoot,
            port = portAllocator.allocate(),
            instanceId = UUID.randomUUID().toString(),
            attempt = attempt,
        )
    }

    @Synchronized
    private fun switchToPreviousRuntime(): RuntimeActivation? {
        val current = runtimeActivation ?: return null
        val fallback = versionRegistry.rollback(runtimeRoot, current) ?: return null
        runtimeActivation = fallback
        installedRuntime = fallback.runtimePath.toFile()
        return fallback
    }

    fun readLogTail(
        maximumLines: Int = 200,
        maximumBytes: Int = 256 * 1024,
    ): String = logStore.tail(maximumLines, maximumBytes)

    fun beginImportCommit(id: String): ImportTransactionManager.Transaction =
        importTransactions.begin(id).also { importAwaitingHealth = it }

    fun commitImport(transaction: ImportTransactionManager.Transaction) {
        importTransactions.commit(transaction)
        importAwaitingHealth = null
    }

    fun rollbackImport(transaction: ImportTransactionManager.Transaction) {
        importTransactions.rollback(transaction)
        importAwaitingHealth = null
    }

    private fun confirmHealthyMigration() {
        val marker = noBackupRoot.resolve("migration/pending-health-confirmation").toFile()
        val committed =
            try {
                migrator.commit(
                    legacyData = legacyData,
                    targetData = dataRoot,
                    journal = migrationJournal,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to retire the verified legacy data copy", error)
                false
            }
        if (committed || marker.exists()) {
            if (marker.exists() && !committed) {
                return
            }
            marker.delete()
            noBackupRoot.resolve("migration/healthy").toFile().writeText(manifest.runtimeId)
        }
    }

    companion object {
        private const val TAG = "AndroidRuntimeBackend"
        private const val RUNTIME_MANIFEST_ASSET = "runtime-manifest.json"
        private const val RUNTIME_BUNDLE_ASSET = "runtime.bundle"
    }
}
