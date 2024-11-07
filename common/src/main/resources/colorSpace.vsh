#version 330 core

in vec3 irisInt_Position;
in vec2 irisInt_UV0;
uniform mat4 projection;
out vec2 uv;

void main() {
    gl_Position = projection * vec4(irisInt_Position, 1.0);
    uv = irisInt_UV0;
}
