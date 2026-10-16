cmake_minimum_required(VERSION 3.18)
project(wiitar_evdev C)

# One JNI function: EVIOCGRAB on an evdev fd (Java has no ioctl).
add_library(wiitar_evdev SHARED evdev.c)
