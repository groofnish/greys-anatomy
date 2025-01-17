package com.github.ompc.greys.core.command.hacking;

import com.github.ompc.greys.core.GlobalOptions;
import com.github.ompc.greys.core.GlobalOptions.Option;
import com.github.ompc.greys.core.command.Command;
import com.github.ompc.greys.core.command.annotation.Cmd;
import com.github.ompc.greys.core.command.annotation.IndexArg;
import com.github.ompc.greys.core.textui.TTable;
import com.github.ompc.greys.core.textui.TTable.ColumnDefine;
import com.github.ompc.greys.core.util.affect.RowAffect;
import com.github.ompc.greys.core.server.Session;
import com.github.ompc.greys.core.util.Matcher;
import org.apache.commons.lang3.reflect.FieldUtils;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;

import static com.github.ompc.greys.core.textui.TTable.Align.LEFT;
import static com.github.ompc.greys.core.util.GaCheckUtils.isIn;
import static com.github.ompc.greys.core.util.GaStringUtils.newString;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.reflect.FieldUtils.writeStaticField;

/**
 * 选项开关命令
 * Created by oldmanpushcart@gmail.com on 15/6/6.
 */
@Cmd(isHacking = true, name = "options", summary = "Greys options",
        eg = {
                "options dump true",
                "options unsafe true"
        }
)
public class OptionsCommand implements Command {

    @IndexArg(index = 0, name = "options-name", isRequired = false, summary = "Option name")
    private String optionName;

    @IndexArg(index = 1, name = "options-value", isRequired = false, summary = "Option value")
    private String optionValue;

    @Override
    public Action getAction() {

        // show the options
        if (isShow()) {
            return doShow();
        }

        // show the options name
        else if (isShowName()) {
            return doShowName();
        }

        // change the name/value
        else {
            return doChangeNameValue();
        }

    }


    /*
     * 判断当前动作是否需要展示整个options
     */
    private boolean isShow() {
        return isBlank(optionName)
                && isBlank(optionValue);
    }

    /*
     * 判断当前动作是否需要展示某个Name的值
     */
    private boolean isShowName() {
        return isNotBlank(optionName)
                && isBlank(optionValue);
    }

    private RowAction doShow() {
        return new RowAction() {
            @Override
            public RowAffect action(Session session, Instrumentation inst, Printer printer) throws Throwable {
                final RowAffect affect = new RowAffect();
                final Collection<Field> fields = findOptions(new Matcher.RegexMatcher(".*"));
                printer.print(drawShowTable(fields)).finish();
                affect.rCnt(fields.size());
                return affect;
            }
        };
    }

    private RowAction doShowName() {
        return new RowAction() {
            @Override
            public RowAffect action(Session session, Instrumentation inst, Printer printer) throws Throwable {
                final RowAffect affect = new RowAffect();
                final Collection<Field> fields = findOptions(new Matcher.EqualsMatcher(optionName));
                printer.print(drawShowTable(fields)).finish();
                affect.rCnt(fields.size());
                return affect;
            }
        };
    }

    private Collection<Field> findOptions(Matcher optionNameMatcher) {
        final Collection<Field> matchFields = new ArrayList<Field>();
        for (final Field optionField : FieldUtils.getAllFields(GlobalOptions.class)) {
            if (!optionField.isAnnotationPresent(Option.class)) {
                continue;
            }

            final Option optionAnnotation = optionField.getAnnotation(Option.class);
            if (optionAnnotation != null
                    && !optionNameMatcher.matching(optionAnnotation.name())) {
                continue;
            }
            matchFields.add(optionField);
        }
        return matchFields;
    }

    private String drawShowTable(Collection<Field> optionFields) throws IllegalAccessException {

        final TTable tTable = new TTable(new ColumnDefine[]{
                new ColumnDefine(6, false, LEFT),
                new ColumnDefine(10, false, LEFT),
                new ColumnDefine(),
                new ColumnDefine(),
                new ColumnDefine(20, false, LEFT),
                new ColumnDefine(50, false, LEFT)
        })
                .addRow("LEVEL", "TYPE", "NAME", "VALUE", "SUMMARY", "DESCRIPTION")
                .padding(1);

        for (final Field optionField : optionFields) {
            final Option optionAnnotation = optionField.getAnnotation(Option.class);
            tTable.addRow(
                    optionAnnotation.level(),
                    optionField.getType().getSimpleName(),
                    optionAnnotation.name(),
                    optionField.get(null),
                    optionAnnotation.summary(),
                    optionAnnotation.description()
            );
        }

        return tTable.rendering();
    }


    private RowAction doChangeNameValue() {
        return new RowAction() {
            @Override
            public RowAffect action(Session session, Instrumentation inst, Printer printer) throws Throwable {

                final RowAffect affect = new RowAffect();
                final Collection<Field> fields = findOptions(new Matcher.EqualsMatcher(optionName));

                // name not exists
                if (fields.isEmpty()) {
                    printer.println(format("options[%s] not found.", optionName)).finish();
                    return affect;
                }

                final Field field = fields.iterator().next();
                final Option optionAnnotation = field.getAnnotation(Option.class);
                final Class<?> type = field.getType();
                final Object beforeValue = FieldUtils.readStaticField(field);
                final Object afterValue;

                try {
                    // try to case string to type
                    if (isIn(type, int.class, Integer.class)) {
                        writeStaticField(field, afterValue = Integer.valueOf(optionValue));
                    } else if (isIn(type, long.class, Long.class)) {
                        writeStaticField(field, afterValue = Long.valueOf(optionValue));
                    } else if (isIn(type, boolean.class, Boolean.class)) {
                        writeStaticField(field, afterValue = Boolean.valueOf(optionValue));
                    } else if (isIn(type, double.class, Double.class)) {
                        writeStaticField(field, afterValue = Double.valueOf(optionValue));
                    } else if (isIn(type, float.class, Float.class)) {
                        writeStaticField(field, afterValue = Float.valueOf(optionValue));
                    } else if (isIn(type, byte.class, Byte.class)) {
                        writeStaticField(field, afterValue = Byte.valueOf(optionValue));
                    } else if (isIn(type, short.class, Short.class)) {
                        writeStaticField(field, afterValue = Short.valueOf(optionValue));
                    } else {
                        printer.println(format("Options[%s] type[%s] desupported.", optionName, type.getSimpleName())).finish();
                        return affect;
                    }

                    affect.rCnt(1);
                } catch (Throwable t) {
                    printer.println(format("Cannot cast option value[%s] to type[%s].", optionValue, type.getSimpleName())).finish();
                    return affect;
                }

                final TTable tTable = new TTable(4)
                        .padding(1)
                        .addRow("NAME", "BEFORE-VALUE", "AFTER-VALUE")
                        .addRow(optionAnnotation.name(), newString(beforeValue), newString(afterValue));

                printer.print(tTable.rendering()).finish();
                return affect;
            }
        };
    }

}
