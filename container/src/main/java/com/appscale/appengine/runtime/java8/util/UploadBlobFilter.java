/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

/**
 *
 */
public class UploadBlobFilter implements Filter {
  private static final Logger logger = Logger.getLogger(UploadBlobFilter.class.getName());

  private static final String UPLOAD_HEADER = "X-AppEngine-BlobUpload";
  private static final String UPLOADED_BLOBKEY_ATTR = "com.google.appengine.api.blobstore.upload.blobkeys";
  private static final String UPLOADED_BLOBINFO_ATTR = "com.google.appengine.api.blobstore.upload.blobinfos";
  private static final String UPLOADED_BLOBINFO_PARAM = "blob_info_metadata";

  public void init(FilterConfig config) {
  }

  public void destroy() {
  }

  public void doFilter(
      final ServletRequest request,
      final ServletResponse response,
      final FilterChain chain
  ) throws IOException, ServletException {
    final HttpServletRequest req = (HttpServletRequest)request;
    if (req.getHeader(UPLOAD_HEADER) != null) {
      final String metadata = request.getParameter(UPLOADED_BLOBINFO_PARAM);
      if (metadata != null) {
        logger.fine("Handling blob upload metadata\n" + metadata);

        final BlobMetadata blobMetadata = BlobMetadata.of(metadata);

        request.setAttribute(UPLOADED_BLOBKEY_ATTR, blobMetadata.getBlobKeys());
        request.setAttribute(UPLOADED_BLOBINFO_ATTR, blobMetadata.getBlobInfos());
      }
    }

    chain.doFilter(request, response);
  }

  static final class BlobMetadata {
    final Map<String, List<String>> blobKeys = new HashMap<>();
    final Map<String, List<HashMap<String, String>>> blobInfos = new HashMap<>();

    static BlobMetadata of(final String metadata) {
      final BlobMetadata blobMetadata = new BlobMetadata();
      blobMetadata.parseMetadata(metadata);
      return blobMetadata;
    }

    public Map<String, List<String>> getBlobKeys() {
      return blobKeys;
    }

    public Map<String, List<HashMap<String, String>>> getBlobInfos() {
      return blobInfos;
    }

    private void parseMetadata(final String metadata) {
      final String strippedDict = metadata.substring(1, metadata.length() - 1);

      // Grab file key from metadata.
      final String file_key = strippedDict.substring(0, strippedDict.indexOf(':')).split("'")[1];

      // Extract BlobKeys and BlobInfo parts from metadata.
      final String partsList = strippedDict.substring(strippedDict.indexOf(':') + 1);
      final List<String> innerKeys = new ArrayList<>();
      final HashMap<String, String> innerAttributes = new HashMap<>();
      final String[] pairs = partsList.substring(2, partsList.length() - 2).split(",");
      for (final String pair : pairs) {
        final String key = pair.substring(0, pair.indexOf(':')).split("'")[1];
        final String value = pair.substring(pair.indexOf(':') + 1).split("'")[1];
        innerAttributes.put(key, value);
        if ("key".equals(key)) {
          innerKeys.add(value);
        }
      }

      // Populate request attributes
      final List<HashMap<String, String>> attrList = new ArrayList<>(innerAttributes.size());
      attrList.add(innerAttributes);

      blobKeys.put(file_key, innerKeys);
      blobInfos.put(file_key, attrList);
    }
  }
}

