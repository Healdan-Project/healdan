// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.metadata.AttributeSet;
import com.logicaldoc.core.metadata.AttributeSetDAO;
import com.logicaldoc.core.metadata.Template;
import com.logicaldoc.core.metadata.TemplateDAO;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.util.io.FileUtil;
import com.logicaldoc.util.io.IOUtil;
import com.logicaldoc.web.AbstractWPTestCase;
import com.logicaldoc.web.UploadServlet;

/**
 * Checks that the Import of the Templates and Attribute sets tabs is refused,
 * to an administrator too, so a file in upstream's format makes no template
 * and no set (Healdan-Project/healdan#245). Each upload is a file the import
 * would otherwise read.
 */
public class MetadataImportsTest extends AbstractWPTestCase {

    private static final String NAME = "Imported";

    @Test
    public void testTemplateImportIsRefused() throws IOException, PersistenceException {
        Template template = new Template();
        template.setName(NAME);
        File file = new File(tempDir, "template-imported.xml");
        FileUtil.writeFile(IOUtil.serialize(template, "UTF-8"), file.getAbsolutePath());
        upload(file);

        ServerException e = assertThrows(ServerException.class, () -> new TemplateServiceImpl().importTemplate());
        assertEquals(HealdanImports.REFUSAL, e.getMessage());
        assertNull(TemplateDAO.get().findByName(NAME, 1L));
    }

    @Test
    public void testAttributeSetImportIsRefused() throws IOException, PersistenceException {
        AttributeSet set = new AttributeSet();
        set.setName(NAME);
        File file = new File(tempDir, "attributeset-imported.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("object.xml"));
            zip.write(IOUtil.serialize(set, "UTF-8").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        upload(file);

        ServerException e = assertThrows(ServerException.class,
                () -> new AttributeSetServiceImpl().importAttributeSet());
        assertEquals(HealdanImports.REFUSAL, e.getMessage());
        assertNull(AttributeSetDAO.get().findByName(NAME, 1L));
    }

    /**
     * Puts the file among the current session's uploads, as the upload
     * servlet does
     */
    private void upload(File file) {
        Map<String, File> uploads = new HashMap<>();
        uploads.put(file.getName(), file);
        session.getDictionary().put(UploadServlet.UPLOADS, uploads);
    }
}
