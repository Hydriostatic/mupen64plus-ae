# Expansões de segunda tela (.exp) — M64-DS

Uma expansão é um arquivo `.exp` que dá a um jogo um painel na tela de baixo (BC). Ela só
contém dados: quais valores ler da memória do jogo e como desenhá-los. Não tem código, então
para fazer um painel para um jogo novo não é preciso mexer no emulador.

**Instalar:** na tela de baixo, toque em **Expansões**, depois em **Importar expansão (.exp)…**
e escolha o arquivo. O emulador copia o arquivo para dentro do app, então depois você pode apagar
o original. Ao abrir um jogo, o emulador usa a expansão que combina com aquela ROM.

## O arquivo

Um `.exp` é um **zip** renomeado, com:

```
manifest.json        obrigatório
images/…             opcional: fundo e peças (PNG/JPG)
icons/…              opcional: ícones dos blocos
fonts/…              opcional: uma fonte .ttf/.otf
```

## manifest.json

```json
{
  "format": 1,
  "id": "banjotooie-usa-exemplo",
  "name": "Banjo-Tooie – Exemplo",
  "game": "Banjo-Tooie (USA)",
  "version": "1.0",
  "author": "Você",

  "match": { "header": "BANJO TOOIE", "country": "E" },

  "theme": { … },
  "screen": { … },
  "values": { … }
}
```

- `id`: nome único. Importar outra expansão com o mesmo `id` substitui a anterior, e é assim
  que se atualiza uma expansão.
- `match`: diz para qual ROM a expansão serve. Use pelo menos um destes:
  - `header`: o começo do nome interno da ROM.
  - `country`: a letra da região (`E` para EUA, `P` para Europa, `J` para Japão).
  - `crc`: o CRC da ROM, como o app mostra.
  - `md5`: o MD5 da ROM. Se estiver presente, só ele é usado.

### theme (tudo opcional)

| chave | o que é |
|---|---|
| `background`, `panel`, `text`, `label`, `accent` | cores `#RRGGBB` |
| `title_colors` | lista de cores, uma por palavra do título; as palavras que sobram ficam com a última cor |
| `font` | caminho da fonte dentro do zip |
| `background_image` | imagem de fundo (preenche a tela) |
| `sign_image`, `tile_image`, `bar_image`, `button_image` | peças desenhadas em 9 partes (veja abaixo) |

Peça em 9 partes: `{"image": "images/tile.png", "insets": [esq, topo, dir, baixo]}`. Os cantos
mantêm o formato e só o meio estica. Também é possível informar, em pixels da própria imagem:

- `label_y`: centro do rótulo, medido a partir do topo (para blocos).
- `value_y`: centro do valor, medido a partir de baixo (para blocos).
- `content_left`: onde os itens começam (para a barra; útil quando a arte já traz uma placa
  desenhada à esquerda).

### screen

```json
"screen": {
  "title": "{world}",
  "columns": 3,
  "tiles": [
    { "label": "Jiggies", "icon": "icons/jiggy.png", "value": "{jiggies}/90" }
  ],
  "bar": { "label": "", "items": [
    { "icon": "icons/feather_red.png", "value": "{red_feathers}" }
  ]}
}
```

Os textos aceitam `{nome}`, que é trocado pelo valor com esse nome. `{time}` já existe: é o
tempo jogado nesta sessão. A tela sempre tem os botões **Opções** (abre o menu do emulador) e
**Salvar e sair**.

### values: lendo a memória

Os endereços podem ser escritos como `0x8011B080` (endereço do N64) ou como offset físico. A
ordem dos bytes já é a do N64 (big-endian).

| `type` | campos | resultado |
|---|---|---|
| `u8` `s8` `u16` `s16` `u32` | `addr`, ou `ptr` + `offset` | número; aceita `times` e `add` opcionais |
| `flags` | `addr` ou `ptr` + `offset`; `bits` (lista); `mode` | quantos bits estão ligados (`count`), ou 1/0 (`any`, `all`) |
| `sum` | `terms`: nomes, ou `{"value": "nome", "times": 5}` | soma |
| `lookup` | `value`, `table`, `default`, `keep_last` | texto da tabela; `keep_last` mantém o último valor encontrado |
| `const` | `value` | fixo |

- `ptr`: um endereço que guarda um ponteiro. O valor é lido em ponteiro + `offset`.
- `bits`: números no formato `(byte << 3) | bit`, contados a partir do endereço base.

Exemplo, com as notas do Banjo-Tooie (ninhos valem 5, claves valem 20):

```json
"nests": { "type": "flags", "ptr": "0x8012C770", "bits": [1063, 1064, …] },
"clefs": { "type": "flags", "ptr": "0x8012C770", "bits": [ … ] },
"notes": { "type": "sum", "terms": [ {"value": "nests", "times": 5}, {"value": "clefs", "times": 20} ] }
```

O exemplo completo está em `examples/expansions/banjotooie.exp`; para ver o manifest, abra o
arquivo como zip.
