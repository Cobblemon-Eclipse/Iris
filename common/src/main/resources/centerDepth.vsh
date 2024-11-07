#version 150 core

in vec3 irisInt_Position;
uniform mat4 projection;

void main() {
    gl_Position = projection * vec4(irisInt_Position, 1.0);
}
