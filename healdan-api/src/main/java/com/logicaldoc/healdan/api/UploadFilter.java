package com.logicaldoc.healdan.api;

import org.apache.cxf.attachment.AttachmentDeserializer;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.apache.cxf.jaxrs.ext.multipart.MultipartBody;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.message.Message;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;

/**
 * Keeps uploads within bounds. Before a request is read, it has CXF stop
 * reading an upload part more than 1 MB over the upload limit, and answer
 * {@code 413}, so that an oversized upload can't fill the disk; it's set on
 * every request, so it follows {@code upload.maxsize}. The limit itself is
 * checked on the stored file ({@link Uploads#maxBytes}), in the order a
 * resource checks its request. After the request, it lets go of every part
 * CXF read, which CXF keeps in a temporary file above 1 MB until it's read:
 * a refused request, or a part no resource takes, would leave it behind.
 */
@PreMatching
public class UploadFilter implements ContainerRequestFilter, ContainerResponseFilter {

	/** How far over the limit CXF still reads a part */
	static final long SLACK = 1024L * 1024L;

	@Override
	public void filter(ContainerRequestContext request) {
		// CXF reads its attachment limit from the message, which the filter's properties aren't
		Message message = JAXRSUtils.getCurrentMessage();
		if (message != null)
			message.put(AttachmentDeserializer.ATTACHMENT_MAX_SIZE, String.valueOf(Uploads.maxBytes() + SLACK));
	}

	@Override
	public void filter(ContainerRequestContext request, ContainerResponseContext response) {
		Message message = JAXRSUtils.getCurrentMessage();
		Message in = message != null && message.getExchange() != null ? message.getExchange().getInMessage() : null;
		// CXF keeps the parts there once a resource has read a multipart body, the first part included
		if (in != null && in.get(MultipartBody.INBOUND_MESSAGE_ATTACHMENTS) instanceof MultipartBody body)
			for (Attachment part : body.getAllAttachments())
				Uploads.release(part.getDataHandler());
	}
}
