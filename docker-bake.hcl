variable "REGISTRY" { default = "docker.io" }
variable "NAMESPACE" { default = "binarycodes" }
variable "APP_NAME" { default = "file-browser" }
variable "APP_VERSION" { default = "1.0-SNAPSHOT" }

variable "TAG_NAME" { default = "file-browser" }

group "default" {
  targets = ["app"]
}

target "app" {
  context    = "."
  dockerfile = "Dockerfile"

  args = {
    APP_NAME    = APP_NAME
    APP_VERSION = APP_VERSION
  }

  tags = [
    "${REGISTRY}/${NAMESPACE}/${TAG_NAME}:${APP_VERSION}",
    "${REGISTRY}/${NAMESPACE}/${TAG_NAME}:latest",
  ]

  platforms = ["linux/amd64", "linux/arm64"]
}
