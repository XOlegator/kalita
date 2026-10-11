package kalita.storage.mdb

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MdbOperationsReaderTest {

    @Test
    fun `counts operations excluding soft-deleted rows`(@TempDir tempDir: Path) {
        val fixture = createOperationsFixture(tempDir.resolve("fixture.mdb").toFile(), operationCount = 12, softDeleteEvery = 4)

        countOperations(fixture) shouldBe 9
    }

    @Test
    fun `counts zero operations in an empty fixture`(@TempDir tempDir: Path) {
        val fixture = createOperationsFixture(tempDir.resolve("empty.mdb").toFile(), operationCount = 0)

        countOperations(fixture) shouldBe 0
    }
}
