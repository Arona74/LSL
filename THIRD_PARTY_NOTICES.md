# Third-party notices

## Geostep (verdantlayers)

The Distant Horizons exact layer heights (`dh/DhLayerMesh`, `dh/IrisLayerShader` and the mixins in
`mixin/dh`) are adapted from Geostep 1.3, distributed under the MIT License. They came to LSL from
AronaLayers-gen, where they were first written.

```
MIT License

Copyright (c) 2026 Verdant Layers contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Distant Horizons

LSL compiles against Distant Horizons' API and core classes (LGPL-3.0) as a compile-only
dependency (`libs/distanthorizons-3.3.3-compile.jar`); none of it is distributed in LSL's jars.

## Voxy

LSL compiles against Voxy (all rights reserved, no redistribution) as a compile-only dependency
that is never committed or shipped; no Voxy code is copied into LSL. LSL only hooks Voxy at
runtime through mixins.

## Forgified Fabric API

The NeoForge module compiles against Fabric's renderer API and API base as Forgified Fabric API
ships them for 1.21.1 (Apache-2.0; `libs/ffapi-fabric-renderer-api-v1-*.jar`,
`libs/ffapi-fabric-api-base-*.jar`), as compile-only dependencies; none of it is distributed in
LSL's jars. At runtime they come from the Forgified Fabric API that Roxy requires.

## Sodium

The NeoForge module compiles against Sodium 0.8.13 for NeoForge 1.21.1 (Polyform Shield License)
as a compile-only dependency that is never committed or shipped (`libs/sodium-*.jar` is
git-ignored); LSL subclasses one of its render contexts at runtime (`SodiumCtmContext`).
