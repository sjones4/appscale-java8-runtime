/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.appscale.appengine.runtime.java8.util.UploadBlobFilter.BlobMetadata;

/**
 *
 */
public class UploadBlobFilterTest {

  @Test
  public void testUploadMetadataKeys() {
    final String metadata = "{u'file': [{'md5-hash': 'b850ea350d7b61b44f45245c1de15ec8', 'filename': u'file1.txt', 'creation-date': '2019-07-03 16:23:35.184215', 'key': '7bzQgLel6db9gr1X2P2Sow==', 'content-type': u'application/octet-stream', 'size': '19'}]}";
    final BlobMetadata blobMetadata = BlobMetadata.of(metadata);

    assertNotNull(blobMetadata.getBlobKeys().get("file"), "file");
    assertEquals(1, blobMetadata.getBlobKeys().get("file").size(), "part count");
    assertEquals("7bzQgLel6db9gr1X2P2Sow==", blobMetadata.getBlobKeys().get("file").get(0));
  }

  @Test
  public void testUploadMetadataInfo() {
    final String metadata = "{u'file': [{'md5-hash': 'b850ea350d7b61b44f45245c1de15ec8', 'filename': u'file1.txt', 'creation-date': '2019-07-03 16:23:35.184215', 'key': '7bzQgLel6db9gr1X2P2Sow==', 'content-type': u'application/octet-stream', 'size': '19'}]}";
    final BlobMetadata blobMetadata = BlobMetadata.of(metadata);

    assertNotNull(blobMetadata.getBlobInfos().get("file"), "file");
    assertEquals(1, blobMetadata.getBlobInfos().get("file").size(), "part count");

    final Map<String,String> filePartInfo = blobMetadata.getBlobInfos().get("file").get(0);
    assertEquals("file1.txt", filePartInfo.get("filename"), "filename");
    assertEquals("2019-07-03 16:23:35.184215", filePartInfo.get("creation-date"), "creation-date");
    assertEquals("7bzQgLel6db9gr1X2P2Sow==", filePartInfo.get("key"), "key");
    assertEquals("19", filePartInfo.get("size"), "size");
    assertEquals("application/octet-stream", filePartInfo.get("content-type"), "content-type");
    assertEquals("b850ea350d7b61b44f45245c1de15ec8", filePartInfo.get("md5-hash"), "md5-hash");
  }
}
