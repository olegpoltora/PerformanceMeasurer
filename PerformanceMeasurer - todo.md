# PerformanceMeasurer - todo

## Логирование

### не выводить 100% при одноразовом выводе

- [x] 

### не выводить значения за данную итерацию при одноразовом выводе (r/s/i: 1;)

```bash
[utils.files.FolderCopier                ] 00:00:15 100% r/s: 1;  r/s/i: 1;  success: 16;  
[cms.preprocessor.ForJbakeProcessor      ] 00:00:14 100% r/s: 0;  r/s/i: 0;  success: 1;  
```

вот так

~~~bash
[utils.files.FolderCopier                ] 00:00:15  r/s: 1;  success: 16;  
[cms.preprocessor.ForJbakeProcessor      ] 00:00:14  r/s: 0;  success: 1;  
~~~



### отступы для изменений

- [x] 

Добавлять отступы для будущих изменений (дельта). 

Учитывать прирост с начала измерений. Плюс 2 скобки, знак(что бы был). `Integer.stringSize(int x)`

Но для мгновенных значений - фиксированный отступ - скобки, знак, одна цифра = 4 символа.

должно получиться:

~~~bash
00:00:15 00:00:15 49%   r/s: 16;      fail: 35% 86;        success: 34% 83;        error: 31% 77;  
00:00:29 00:00:12 70%   r/s: 11(-5);  fail: 34% 121(+35);  success: 34% 120(+37);  error: 31% 110(+33);
~~~

### не выводить отступы если сразу вывел все

- [x] 

## %

### possibleSize - Сброс счетчика

При вызове `.possibleSize()` сбрасывать счетчик, т.к. по сути - это новый отчет. К тому же не будет ошибки с отображением более 100 процентов.

