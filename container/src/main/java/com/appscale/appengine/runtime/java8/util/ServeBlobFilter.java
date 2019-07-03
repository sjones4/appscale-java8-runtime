/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import com.google.appengine.api.blobstore.BlobInfo;
import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.ByteRange;
import com.google.appengine.api.blobstore.RangeFormatException;
import com.google.appengine.api.blobstore.dev.BlobInfoStorage;
import com.google.appengine.api.blobstore.dev.BlobStorage;
import com.google.appengine.api.blobstore.dev.BlobStorageFactory;
import com.google.appengine.repackaged.com.google.common.io.Closeables;
import com.google.appengine.tools.development.ApiProxyLocal;

/**
 * Blob serving via DatastoreBlobStorage
 */
public class ServeBlobFilter implements Filter {
  private static final Logger logger = Logger.getLogger(ServeBlobFilter.class.getName());

  private static final String CONTEXT_ATTR_APIPROXY = "com.google.appengine.devappserver.ApiProxyLocal";

  private static final String SERVE_HEADER = "X-AppEngine-BlobKey";
  private static final String BLOB_RANGE_HEADER = "X-AppEngine-BlobRange";
  private static final String CONTENT_RANGE_HEADER = "Content-Range";
  private static final String RANGE_HEADER = "Range";
  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String CONTENT_RANGE_FORMAT = "bytes %d-%d/%d";
  private static final int BUF_SIZE = 4096;

  private BlobStorage blobStorage;
  private BlobInfoStorage blobInfoStorage;
  private ApiProxyLocal apiProxyLocal;

  public void init(final FilterConfig config) {
    this.blobInfoStorage = BlobStorageFactory.getBlobInfoStorage();
    this.apiProxyLocal = (ApiProxyLocal)config.getServletContext().getAttribute(CONTEXT_ATTR_APIPROXY);
  }

  public void doFilter(
      final ServletRequest request,
      final ServletResponse response,
      final FilterChain chain
  ) throws IOException, ServletException {
    final ServeBlobFilter.ResponseWrapper wrapper =
        new ServeBlobFilter.ResponseWrapper((HttpServletResponse)response);
    chain.doFilter(request, wrapper);
    final BlobKey blobKey = wrapper.getBlobKey();
    if (blobKey != null) {
      this.serveBlob(blobKey, wrapper.hasContentType(), (HttpServletRequest)request, wrapper);
    }

  }

  public void destroy() {
  }

  private BlobStorage getBlobStorage() {
    if (this.blobStorage == null) {
      this.apiProxyLocal.getService("blobstore");
      // AppScale: Use datastore for blobs
      this.blobStorage = new DatastoreBlobStorage(this.blobInfoStorage);
    }

    return this.blobStorage;
  }

  private void calculateContentRange(
      final BlobInfo blobInfo,
      final HttpServletRequest request,
      final HttpServletResponse response
  ) throws RangeFormatException {
    final ServeBlobFilter.ResponseWrapper responseWrapper = (ServeBlobFilter.ResponseWrapper)response;
    final long blobSize = blobInfo.getSize();
    String rangeHeader = responseWrapper.getBlobRangeHeader();
    if (rangeHeader != null) {
      if (rangeHeader.isEmpty()) {
        response.setHeader(BLOB_RANGE_HEADER, null);
        rangeHeader = null;
      }
    } else {
      rangeHeader = request.getHeader(RANGE_HEADER);
    }

    if (rangeHeader != null) {
      final ByteRange byteRange = ByteRange.parse(rangeHeader);
      String contentRangeHeader;
      if (byteRange.hasEnd()) {
        contentRangeHeader = String.format(CONTENT_RANGE_FORMAT, byteRange.getStart(), byteRange.getEnd(), blobSize);
      } else {
        long contentRangeStart;
        if (byteRange.getStart() >= 0L) {
          contentRangeStart = byteRange.getStart();
        } else {
          contentRangeStart = blobSize + byteRange.getStart();
        }

        contentRangeHeader = String.format(CONTENT_RANGE_FORMAT, contentRangeStart, blobSize - 1L, blobSize);
      }

      response.setHeader(CONTENT_RANGE_HEADER, contentRangeHeader);
    }

  }

  private static void copy(
      final InputStream from,
      final OutputStream to,
      long size
  ) throws IOException {
    int r;
    for(byte[] buf = new byte[BUF_SIZE]; size > 0L; size -= (long)r) {
      r = from.read(buf);
      if (r == -1) {
        return;
      }

      to.write(buf, 0, (int)Math.min((long)r, size));
    }
  }

  private void serveBlob(
      final BlobKey blobKey,
      final boolean hasContentType,
      final HttpServletRequest request,
      final HttpServletResponse response
  ) throws IOException {
    if (response.isCommitted()) {
      logger.warning("Asked to send blob " + blobKey + " but response was already committed.");
    } else {
      BlobInfo blobInfo = this.blobInfoStorage.loadBlobInfo(blobKey);
      if (blobInfo == null) {
        blobInfo = this.blobInfoStorage.loadGsFileInfo(blobKey);
      }

      String contentRange;
      if (blobInfo == null) {
        logger.warning("Could not find blob: " + blobKey);
        response.sendError(404);
      } else if (!this.getBlobStorage().hasBlob(blobKey)) {
        logger.warning("Blob " + blobKey + " missing. Did you delete the file?");
        response.sendError(404);
      } else {
        if (!hasContentType) {
          response.setContentType(this.getContentType(blobKey));
        }

        try {
          this.calculateContentRange(blobInfo, request, response);
          contentRange = ((ServeBlobFilter.ResponseWrapper)response).getContentRangeHeader();
          long contentLength = blobInfo.getSize();
          long start = 0L;
          if (contentRange != null) {
            ByteRange byteRange = ByteRange.parseContentRange(contentRange);
            start = byteRange.getStart();
            contentLength = byteRange.getEnd() - byteRange.getStart() + 1L;
            response.setStatus(206);
          }

          response.setHeader("Content-Length", Long.toString(contentLength));
          boolean swallowDueToThrow = true;
          final InputStream inStream = this.getBlobStorage().fetchBlob(blobKey);

          try {
            final ServletOutputStream outStream = response.getOutputStream();
            try {
              inStream.skip(start);
              copy(inStream, outStream, contentLength);
              swallowDueToThrow = false;
            } finally {
              Closeables.close(outStream, swallowDueToThrow);
            }
          } finally {
            Closeables.close(inStream, swallowDueToThrow);
          }

        } catch (RangeFormatException var24) {
          response.setStatus(416);
        }
      }
    }
  }

  private String getContentType(final BlobKey blobKey) {
    final BlobInfo blobInfo = this.blobInfoStorage.loadBlobInfo(blobKey);
    return blobInfo != null ? blobInfo.getContentType() : "application/octet-stream";
  }

  public static class ResponseWrapper extends HttpServletResponseWrapper {
    private BlobKey blobKey;
    private boolean hasContentType;
    private String contentRangeHeader;
    private String blobRangeHeader;

    public ResponseWrapper(HttpServletResponse response) {
      super(response);
    }

    public void setContentType(final String contentType) {
      super.setContentType(contentType);
      this.hasContentType = true;
    }

    public void addHeader(final String name, final String value) {
      if (name.equalsIgnoreCase(SERVE_HEADER)) {
        this.blobKey = new BlobKey(value);
      } else if (name.equalsIgnoreCase(CONTENT_RANGE_HEADER)) {
        this.contentRangeHeader = value;
        super.addHeader(name, value);
      } else if (name.equalsIgnoreCase(BLOB_RANGE_HEADER)) {
        this.blobRangeHeader = value;
        super.addHeader(name, value);
      } else if (name.equalsIgnoreCase(CONTENT_TYPE_HEADER)) {
        this.hasContentType = true;
        super.addHeader(name, value);
      } else {
        super.addHeader(name, value);
      }

    }

    public void setHeader(final String name, final String value) {
      if (name.equalsIgnoreCase(SERVE_HEADER)) {
        this.blobKey = new BlobKey(value);
      } else if (name.equalsIgnoreCase(CONTENT_RANGE_HEADER)) {
        this.contentRangeHeader = value;
        super.setHeader(name, value);
      } else if (name.equalsIgnoreCase(BLOB_RANGE_HEADER)) {
        this.blobRangeHeader = value;
      } else if (name.equalsIgnoreCase(CONTENT_TYPE_HEADER)) {
        this.hasContentType = true;
        super.setHeader(name, value);
      } else {
        super.setHeader(name, value);
      }
    }

    public boolean containsHeader(final String name) {
      if (name.equalsIgnoreCase(SERVE_HEADER)) {
        return this.blobKey != null;
      } else {
        return super.containsHeader(name);
      }
    }

    public BlobKey getBlobKey() {
      return this.blobKey;
    }

    public boolean hasContentType() {
      return this.hasContentType;
    }

    public String getContentRangeHeader() {
      return this.contentRangeHeader;
    }

    public String getBlobRangeHeader() {
      return this.blobRangeHeader;
    }
  }
}
