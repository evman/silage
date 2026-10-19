/* Read-only KMS connector snapshot. Never acquires DRM master or sets properties. */
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <xf86drmMode.h>

static void string(const char *s)
{
    putchar('"');
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') printf("\\%c", c);
        else if (c < 32) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}

static void hex(const void *data, size_t size)
{
    const unsigned char *p = data;
    putchar('"');
    for (size_t i = 0; i < size; i++) printf("%02x", p[i]);
    putchar('"');
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "Usage: kms-snapshot /dev/dri/cardN DP-N\n");
        return 2;
    }
    int fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    if (fd < 0) { perror("open DRM card"); return 1; }
    drmModeRes *resources = drmModeGetResources(fd);
    if (!resources) { perror("get KMS resources"); close(fd); return 1; }
    drmModeConnector *connector = NULL;
    for (int i = 0; i < resources->count_connectors; i++) {
        drmModeConnector *c = drmModeGetConnectorCurrent(fd, resources->connectors[i]);
        if (!c) continue;
        char name[64];
        snprintf(name, sizeof(name), "%s-%u",
                 drmModeGetConnectorTypeName(c->connector_type), c->connector_type_id);
        if (!strcmp(name, argv[2])) { connector = c; break; }
        drmModeFreeConnector(c);
    }
    drmModeFreeResources(resources);
    if (!connector) {
        fprintf(stderr, "Connector %s not found on %s\n", argv[2], argv[1]);
        close(fd); return 1;
    }
    uint32_t crtc_id = 0;
    if (connector->encoder_id) {
        drmModeEncoder *encoder = drmModeGetEncoder(fd, connector->encoder_id);
        if (!encoder) { perror("get connector encoder"); drmModeFreeConnector(connector); close(fd); return 1; }
        crtc_id = encoder->crtc_id;
        drmModeFreeEncoder(encoder);
    }
    drmModeObjectProperties *properties = drmModeObjectGetProperties(
        fd, connector->connector_id, DRM_MODE_OBJECT_CONNECTOR);
    if (!properties) {
        perror("get connector properties");
        drmModeFreeConnector(connector); close(fd); return 1;
    }
    /* Gather everything before writing JSON so a partial read cannot look successful. */
    drmModePropertyRes **descriptors = calloc(properties->count_props, sizeof(*descriptors));
    if (!descriptors) { perror("allocate properties"); return 1; }
    drmModePropertyBlobRes *hdr = NULL;
    int result = 0;
    for (uint32_t i = 0; i < properties->count_props; i++) {
        descriptors[i] = drmModeGetProperty(fd, properties->props[i]);
        if (!descriptors[i]) { perror("get property"); result = 1; break; }
        if (!strcmp(descriptors[i]->name, "HDR_OUTPUT_METADATA") && properties->prop_values[i]) {
            hdr = drmModeGetPropertyBlob(fd, properties->prop_values[i]);
            if (!hdr) { perror("get HDR blob"); result = 1; break; }
        }
    }
    if (!result) {
        printf("{\"connector\":"); string(argv[2]);
        printf(",\"connector_id\":%u,\"crtc_id\":%u,\"connected\":%s,\"properties\":{",
               connector->connector_id, crtc_id,
               connector->connection == DRM_MODE_CONNECTED ? "true" : "false");
        for (uint32_t i = 0; i < properties->count_props; i++) {
            if (i) putchar(',');
            string(descriptors[i]->name);
            printf(":{\"value\":%" PRIu64 ",\"enums\":{", properties->prop_values[i]);
            for (int j = 0; j < descriptors[i]->count_enums; j++) {
                if (j) putchar(',');
                string(descriptors[i]->enums[j].name);
                printf(":%" PRIu64, (uint64_t)descriptors[i]->enums[j].value);
            }
            printf("}}");
        }
        printf("},\"hdr_output_metadata_hex\":");
        if (hdr) hex(hdr->data, hdr->length); else printf("null");
        printf("}\n");
    }
    if (hdr) drmModeFreePropertyBlob(hdr);
    for (uint32_t i = 0; i < properties->count_props; i++)
        if (descriptors[i]) drmModeFreeProperty(descriptors[i]);
    free(descriptors);
    drmModeFreeObjectProperties(properties);
    drmModeFreeConnector(connector);
    close(fd);
    return result;
}
