package com.logicaldoc.core.conversion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.util.SystemUtil;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

/**
 * Test case for {@link PopplerConverter}. Poppler is replaced by a fake
 * pdftoppm that records the arguments it receives, one per line.
 *
 * @since 9.3.1
 */
public class PopplerConverterTest extends AbstractCoreTestCase {

    private File workDir;

    private File recordedArguments;

    @Before
    @Override
    public void setUp() throws IOException, SQLException, PluginException {
        super.setUp();

        // The fake pdftoppm is a shell script
        assumeFalse(SystemUtil.isWindows());

        workDir = new File(tempDir, "poppler");
        workDir.mkdirs();
        recordedArguments = new File(workDir, "arguments.txt");

        File pdftoppm = new File(workDir, "pdftoppm");
        Files.writeString(pdftoppm.toPath(),
                "#!/bin/sh\nprintf '%%s\\n' \"$@\" > '%s'\n".formatted(recordedArguments.getAbsolutePath()));
        pdftoppm.setExecutable(true);

        Context.get().getConfig().setProperty("converter.PopplerConverter.pdftoppm", pdftoppm.getAbsolutePath());
        Context.get().getConfig().setProperty("converter.PopplerConverter.enabled", "true");
    }

    @Test
    public void testPrintPassesArgumentsVerbatim() throws IOException {
        File src = new File(workDir, "source.pdf");
        FileUtil.copyResource("small.pdf", src);

        new PopplerConverter().print(src, new File(workDir, "thumb.png"), 1);

        assertEquals(List.of("-png", "-r", "150", "-f", "1", "-l", "1", src.getAbsolutePath(),
                new File(workDir, "thumb").getAbsolutePath()), readRecordedArguments());
    }

    @Test
    public void testPrintTiffPassesArgumentsVerbatim() throws IOException {
        File src = new File(workDir, "source.pdf");
        FileUtil.copyResource("small.pdf", src);

        new PopplerConverter().print(src, new File(workDir, "page.tif"), 2, 300);

        assertEquals(List.of("-tiff", "-tiffcompression", "lzw", "-r", "300", "-f", "2", "-l", "2",
                src.getAbsolutePath(), new File(workDir, "page").getAbsolutePath()), readRecordedArguments());
    }

    @Test
    public void testPrintWithSpacesInPaths() throws IOException {
        File folder = new File(workDir, "my documents");
        folder.mkdirs();
        File src = new File(folder, "annual report.pdf");
        FileUtil.copyResource("small.pdf", src);

        new PopplerConverter().print(src, new File(folder, "annual report.png"), 1);

        List<String> arguments = readRecordedArguments();
        assertEquals(List.of(src.getAbsolutePath(), new File(folder, "annual report").getAbsolutePath()),
                arguments.subList(arguments.size() - 2, arguments.size()));
    }

    @Test
    public void testConvertToTiffWithSpacesInPaths() throws IOException {
        File folder = new File(workDir, "my documents");
        folder.mkdirs();
        File src = new File(folder, "annual report.pdf");
        FileUtil.copyResource("small.pdf", src);
        File dest = new File(folder, "annual report.tif");

        // The fake writes no image, so the converter reports an empty
        // conversion after running it
        assertThrows(IOException.class, () -> new PopplerConverter().convert(src, dest));

        assertEquals(List.of("-singlefile", "-tiff", "-tiffcompression", "lzw", src.getAbsolutePath(),
                dest.getAbsolutePath()), readRecordedArguments());
    }

    private List<String> readRecordedArguments() throws IOException {
        return Files.readAllLines(recordedArguments.toPath());
    }
}
