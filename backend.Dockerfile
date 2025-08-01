FROM ubuntu:24.04

# copy the native image executable to the image
COPY app app
EXPOSE 8080
CMD ["./app"]
