package org.kie.cekit.cacher.resources;

import org.kie.cekit.cacher.objects.PlainArtifact;
import org.kie.cekit.cacher.utils.CacherUtils;
import org.kie.cekit.cacher.utils.UrlUtils;

import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.MethodHandles;
import java.net.URLDecoder;
import java.util.List;
import java.util.logging.Logger;

@Path("/resource")
public class CacherResource {

    private Logger log = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

    @Inject
    CacherUtils cacherUtils;

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/fetch/{url}")
    public Response fetch(@PathParam("url") String url) throws UnsupportedEncodingException {

        url = URLDecoder.decode(url, "UTF-8");

        StringBuilder responseMessage = new StringBuilder();
        if (UrlUtils.isUrlValid(url)) {
            responseMessage.append(cacherUtils.fetchFile(url));
        } else {
            responseMessage.append("Failed to fetch artifact, please check the url and try again");
        }
        return Response.ok(responseMessage.toString()).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Path("/{checksum}")
    public Response getArtifact(@PathParam("checksum") String checksum) {
        log.info("Querying artifact " + checksum);

        if (cacherUtils.fileExists(checksum)) {

            java.nio.file.Path file2download = cacherUtils.getFile(checksum).get();
            Response.ResponseBuilder response = Response.ok(file2download.toFile());
            response.header("Content-Disposition", "attachment;filename=" + file2download.getFileName());

            log.info("File download successfully requested: " + file2download.toFile());

            return response.build();
        } else {
            log.info("File not found " + checksum);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @DELETE
    @Produces(MediaType.TEXT_PLAIN)
    public Response deleteArtifact(List<String> checksum) {
        StringBuilder response = new StringBuilder("\n");
        for (String ck : checksum) {
            log.info("file received for deletion " + ck);
            if (cacherUtils.deleteArtifact(ck)) {
               response.append("File " + ck + " deleted.\n");
            } else {
                response.append("Fail to delete " + ck + ".\n");
            }
        }
        return Response.ok().entity(response.toString()).build();

    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/")
    public PlainArtifact[] listArtifacts() {
        log.fine("Returning list of artifacts");
        return cacherUtils.getPersistedArtifacts().toArray(new PlainArtifact[0]);
    }

}