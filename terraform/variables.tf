variable "host_port" {
  description = "Port on your PC"
  type        = number
  default     = 8080
}

variable "data_gov_sg_api_key" {
  description = "Optional data.gov.sg API key"
  type        = string
  default     = ""
  sensitive   = true
}

output "url" {
  value = "http://localhost:${var.host_port}/simple-web-app/"
}