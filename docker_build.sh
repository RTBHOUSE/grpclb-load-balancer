#!/usr/bin/env bash

# based on pattern described here: http://blog.alexellis.io/mutli-stage-docker-builds/

set -e

function build_source_in_docker {
  local project=$1

  local builders_share_volume=builders_share_${project}
  local project_datestamp=${project}-$(date +"%s")
  local builder_image=builder-image-${project_datestamp}
  local builder_instance=builder-instance-${project_datestamp}

  # create volume to store files between builds - for example for .m2 cache for maven builds
  docker volume create --name "${builders_share_volume}"

  # build builder image for given project
  docker build -t ${builder_image} -f ${project}/Dockerfile.build .

  # run build with mounted builders_share
  if docker run --name ${builder_instance} -v "${builders_share_volume}:/root/builders_share" ${builder_image}; then
    # copy build artifacts from builder docker instance
    docker cp ${builder_instance}:/sources/${project}/target/. ${project}/target
    docker rm ${builder_instance}
    docker rmi ${builder_image}
  else
    # clean up and exit 1 error in case of build failure
    docker rm ${builder_instance}
    docker rmi ${builder_image}
    echo "error during build inside docker"
    exit 1
  fi
}

function build_final_docker_image {
  local project=$1
  # build final image basing on build artifact stored in ${project}/target directory
  docker build -t "${project}:$(git rev-parse HEAD)" -f ${project}/Dockerfile ${project}
}

if [[ -f ${1}/Dockerfile.build ]]; then
  build_source_in_docker "${1}"
fi
build_final_docker_image "${1}"
