FROM ubuntu:24.04

# copy the native image executable to the image
COPY worker worker
CMD ["./worker"]
