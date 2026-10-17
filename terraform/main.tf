terraform {
  required_version = ">= 1.4"
  required_providers {
    docker = {
      source  = "kreuzwerker/docker"
      version = "~> 3.0"
    }
  }
}

# Docker Desktop on Windows
provider "docker" {
  host = "npipe:////./pipe/docker_engine"
}

locals {
  root = abspath("${path.module}/..")

  # Changes whenever the code, pom.xml or Dockerfile changes
  source_hash = sha1(join("", concat(
    [for f in fileset(local.root, "src/**") : filesha1("${local.root}/${f}")],
    [filesha1("${local.root}/pom.xml"), filesha1("${local.root}/Dockerfile")]
  )))
}

# Runs `docker build` (fast, cached) whenever the sources change
resource "terraform_data" "build" {
  triggers_replace = local.source_hash

  provisioner "local-exec" {
    working_dir = local.root
    command     = "docker build -t simple-web-app:local ."
  }
}

# Looks up the image that was just built
data "docker_image" "app" {
  name       = "simple-web-app:local"
  depends_on = [terraform_data.build]
}

resource "docker_container" "app" {
  name    = "simple-web-app"
  image   = data.docker_image.app.id
  restart = "unless-stopped"

  ports {
    internal = 8080
    external = var.host_port
  }

  env = var.data_gov_sg_api_key == "" ? [] : ["DATA_GOV_SG_API_KEY=${var.data_gov_sg_api_key}"]
}
