package ar.vrx_design.geodac_ble_setup_utility;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    private boolean localEchoEnabled = false;
    private ToggleButton tb_connect;
    private Button btn_get_version;
    private TextView tv_version;
    private Button btn_get_serial, btn_set_serial;
    private EditText et_serial;
    private Button btn_get_sens_cfg, btn_set_sens_cfg;
    private EditText et_sens_cfg;
    private CheckBox cb_in1_ana, cb_in1_dig, cb_in1_2ana, cb_in1_2dig;
    private CheckBox cb_in2_ana, cb_in2_dig, cb_out2_st;
    private short SensorCfg;
    private boolean in1_ana, in1_dig, in1_2ana, in1_2dig;
    private boolean in2_ana, in2_dig, out2_st;
    private Button btn_get_busy_source, btn_set_busy_source;
    private Spinner sp_busy_source;
    private Button btn_get_odom_constant, btn_set_odom_constant;
    private EditText et_odom_constant;
    private Button btn_get_odom_value, btn_set_odom_value;
    private EditText et_odom_value;
    private Button btn_get_odom_divider, btn_set_odom_divider;
    private Spinner sp_odom_divider;
    private Button btn_get_pwron_counter, btn_set_pwron_counter;
    private EditText et_pwron_counter;
    private Button btn_reset;
    private String msg2 = "";

    ArrayList<Byte> data2 = new ArrayList<>();
    boolean timerActive;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */

    public void getSensorCfgBits(byte[] Bytes) {
        in1_ana  = ((Bytes[1] >> 0) & 0x01) > 0;
        in1_dig  = ((Bytes[1] >> 1) & 0x01) > 0;
        in1_2ana = ((Bytes[1] >> 2) & 0x01) > 0;
        in1_2dig = ((Bytes[1] >> 3) & 0x01) > 0;
        in2_ana  = ((Bytes[1] >> 4) & 0x01) > 0;
        in2_dig  = ((Bytes[1] >> 5) & 0x01) > 0;
        out2_st  = ((Bytes[1] >> 6) & 0x01) > 0;
    }

    public void setSensorCfgBits() {
        if(in1_2dig)        // si junto los Sensores Digitales
        {
            in1_dig= true;  // habilito como Entrada Digital In1/Rear
            in2_dig= false; // y deshabilito Entrada Digital In2/Front
        }
        if(in1_2ana)        // si junto los Sensores Analógicos
        {
            in1_ana= true;  // habilito Entrada Analógica In1/Rear
        }
        if( in2_ana ||      // si habilito la Entrada In2/Front, inhibo la Salida de Estado (y viceversa)
            in2_dig )
        {
            out2_st= false;
        }
        else
        if(out2_st)
        {
            in2_ana= false;
            in2_dig= false;
        }
    }

    public void updateSensorCfg() {
        cb_in1_ana.setChecked(in1_ana);
        cb_in1_dig.setChecked(in1_dig);
        cb_in1_2ana.setChecked(in1_2ana);
        cb_in1_2dig.setChecked(in1_2dig);
        cb_in2_ana.setChecked(in2_ana);
        cb_in2_dig.setChecked(in2_dig);
        cb_out2_st.setChecked(out2_st);

        SensorCfg = 0;
        if(in1_ana)
            SensorCfg |=  (0x01 << 0);
        else
            SensorCfg &= ~(0x01 << 0);
        if(in1_dig)
            SensorCfg |=  (0x01 << 1);
        else
            SensorCfg &= ~(0x01 << 1);
        if(in1_2ana)
            SensorCfg |=  (0x01 << 2);
        else
            SensorCfg &= ~(0x01 << 2);
        if(in1_2dig)
            SensorCfg |=  (0x01 << 3);
        else
            SensorCfg &= ~(0x01 << 3);
        if(in2_ana)
            SensorCfg |=  (0x01 << 4);
        else
            SensorCfg &= ~(0x01 << 4);
        if(in2_dig)
            SensorCfg |=  (0x01 << 5);
        else
            SensorCfg &= ~(0x01 << 5);
        if(out2_st)
            SensorCfg |=  (0x01 << 6);
        else
            SensorCfg &= ~(0x01 << 6);
    }

    public boolean validateEditTextStr(@NonNull EditText et, String regex, String error) {
        String str = et.getText().toString();
        if (str.length() == 0) {
            et.requestFocus();
            et.setError("Completar");
        } else if (!str.matches(regex)) {
            et.requestFocus();
            et.setError(error);
        } else return true;
        return false;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));

        //ENABLE AT COMMAND MODE
        tb_connect = view.findViewById(R.id.tb_connect);
        tb_connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(tb_connect.isChecked()) {
                    sendText.setText("bac77ERI#");
                } else {
                    sendText.setText("AT+EXIT=1\n");
                }
                send(sendText.getText().toString());
            }
        });

        //FIRMWARE_VERSION
        btn_get_version = view.findViewById(R.id.btn_get_version);
        btn_get_version.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+VER?\n");
                send(sendText.getText().toString());
            }
        });
        tv_version = view.findViewById(R.id.tv_version);

        //FIRMWARE_SERIAL
        final String regex_0toFFFFFFFFFFFF = "[0-9a-fA-F]{1,12}";
        btn_get_serial = view.findViewById(R.id.btn_get_serial);
        btn_get_serial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+SN?\n");
                send(sendText.getText().toString());
            }
        });
        et_serial = view.findViewById(R.id.et_serial);
        et_serial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                validateEditTextStr(et_serial, regex_0toFFFFFFFFFFFF, "(0x0 a 0xFFFFFFFFFFFF)");
            }
        });
        btn_set_serial = view.findViewById(R.id.btn_set_serial);
        btn_set_serial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String SerialNStr = et_serial.getText().toString();
                if(validateEditTextStr(et_serial, regex_0toFFFFFFFFFFFF, "(0x0 a 0xFFFFFFFFFFFF)")) {
                    sendText.setText("AT+SN="+SerialNStr+"\n");
                    send(sendText.getText().toString());
                }
            }
        });

        //SENSOR_CONFIGURATION
        final String regex_0toFFFF = "[0-9a-fA-F]{1,4}";
        btn_get_sens_cfg = view.findViewById(R.id.btn_get_sens_cfg);
        btn_get_sens_cfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+SENCFG?\n");
                send(sendText.getText().toString());
            }
        });
        et_sens_cfg = view.findViewById(R.id.et_sens_cfg);
        et_sens_cfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                validateEditTextStr(et_sens_cfg, regex_0toFFFF, "(0x0 a 0xFFFF)");
            }
        });
        btn_set_sens_cfg = view.findViewById(R.id.btn_set_sens_cfg);
        btn_set_sens_cfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String SensorCfgStr = et_sens_cfg.getText().toString();
                if(validateEditTextStr(et_sens_cfg, regex_0toFFFF, "(0x0 a 0xFFFF)")) {
                    setSensorCfgBits();
                    updateSensorCfg();

                    et_sens_cfg.setText(String.format("%04X",SensorCfg));
                    SensorCfgStr = et_sens_cfg.getText().toString();

                    sendText.setText("AT+SENCFG="+SensorCfgStr+"\n");
                    send(sendText.getText().toString());
                }
            }
        });
        //IN/OUT1
        cb_in1_ana = view.findViewById(R.id.cb_in1_ana);
        cb_in1_ana.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                in1_ana = cb_in1_ana.isChecked();
                if(in1_ana)
                    SensorCfg |=  (0x01 << 0);
                else
                    SensorCfg &= ~(0x01 << 0);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });
        cb_in1_dig = view.findViewById(R.id.cb_in1_dig);
        cb_in1_dig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                in1_dig = cb_in1_dig.isChecked();
                if(in1_dig)
                    SensorCfg |=  (0x01 << 1);
                else
                    SensorCfg &= ~(0x01 << 1);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });
        cb_in1_2ana = view.findViewById(R.id.cb_in1_2ana);
        cb_in1_2ana.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                in1_2ana = cb_in1_2ana.isChecked();
                if(in1_2ana)
                    SensorCfg |=  (0x01 << 2);
                else
                    SensorCfg &= ~(0x01 << 2);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });
        cb_in1_2dig = view.findViewById(R.id.cb_in1_2dig);
        cb_in1_2dig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                in1_2dig = cb_in1_2dig.isChecked();
                if(in1_2dig)
                    SensorCfg |=  (0x01 << 3);
                else
                    SensorCfg &= ~(0x01 << 3);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });
        //IN/OUT2
        cb_in2_ana = view.findViewById(R.id.cb_in2_ana);
        cb_in2_ana.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                in2_ana = cb_in2_ana.isChecked();
                if(in2_ana)
                    SensorCfg |=  (0x01 << 4);
                else
                    SensorCfg &= ~(0x01 << 4);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });
        cb_in2_dig = view.findViewById(R.id.cb_in2_dig);
        cb_in2_dig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                in2_dig = cb_in2_dig.isChecked();
                if(in2_dig)
                    SensorCfg |=  (0x01 << 5);
                else
                    SensorCfg &= ~(0x01 << 5);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });
        cb_out2_st = view.findViewById(R.id.cb_out2_st);
        cb_out2_st.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                out2_st = cb_out2_st.isChecked();
                if(out2_st)
                    SensorCfg |=  (0x01 << 6);
                else
                    SensorCfg &= ~(0x01 << 6);
                et_sens_cfg.setText(String.format("%04X",SensorCfg));
            }
        });

        //BUSY_SOURCE
        btn_get_busy_source = view.findViewById(R.id.btn_get_busy_source);
        btn_get_busy_source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+REMIS?\n");
                send(sendText.getText().toString());
            }
        });
        sp_busy_source = view.findViewById(R.id.sp_busy_source);
        ArrayAdapter<CharSequence> ad_busy_source = ArrayAdapter.createFromResource(getActivity(),
                R.array.busy_source, android.R.layout.simple_spinner_dropdown_item);
        ad_busy_source.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_busy_source.setAdapter(ad_busy_source);
        btn_set_busy_source = view.findViewById(R.id.btn_set_busy_source);
        btn_set_busy_source.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(sp_busy_source.getSelectedItemPosition() == 0)
                    sendText.setText("AT+REMIS=1\n");
                else
                    sendText.setText("AT+REMIS=0\n");
                send(sendText.getText().toString());
            }
        });

        //ODOM_CONSTANT
        final String regex_1to59999 = "^([1-9]|[1-9][0-9]|[1-9][0-9]{2}|[1-9][0-9]{3}|[1-5][0-9]{4})$";
        btn_get_odom_constant = view.findViewById(R.id.btn_get_odom_constant);
        btn_get_odom_constant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+ODOMC?\n");
                send(sendText.getText().toString());
            }
        });
        et_odom_constant = view.findViewById(R.id.et_odom_constant);
        et_odom_constant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                validateEditTextStr(et_odom_constant, regex_1to59999, "(1 a 59999)");
            }
        });
        btn_set_odom_constant = view.findViewById(R.id.btn_set_odom_constant);
        btn_set_odom_constant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String OdometerConstStr = et_odom_constant.getText().toString();
                if(validateEditTextStr(et_odom_constant, regex_1to59999, "(1 a 59999)")) {
                    sendText.setText("AT+ODOMC="+OdometerConstStr+"\n");
                    send(sendText.getText().toString());
                }
            }
        });

        //ODOM_VALUE
        final String regex_0to9999999999 = "^([0-9]{1,10})$";
        btn_get_odom_value = view.findViewById(R.id.btn_get_odom_value);
        btn_get_odom_value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+ODOMV?\n");
                send(sendText.getText().toString());
            }
        });
        et_odom_value =view.findViewById(R.id.et_odom_value);
        et_odom_value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                validateEditTextStr(et_odom_value, regex_0to9999999999, "(0 a 4294967295)");
            }
        });
        btn_set_odom_value = view.findViewById(R.id.btn_set_odom_value);
        btn_set_odom_value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String OdometerValStr = et_odom_value.getText().toString();
                if(validateEditTextStr(et_odom_value, regex_0to9999999999, "(0 a 4294967295)")) {
                    long OdometerVal = Long.getLong(OdometerValStr);
                    if(OdometerVal > 4294967295L) {
                        et_odom_value.requestFocus();
                        et_odom_value.setError("(0 a 4294967295)");
                    }
                    else {
                        sendText.setText("AT+ODOMV="+OdometerValStr+"\n");
                        send(sendText.getText().toString());
                    }
                }
            }
        });

        //ODOM_DIVIDER
        btn_get_odom_divider = view.findViewById(R.id.btn_get_odom_divider);
        btn_get_odom_divider.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+ODOMD?\n");
                send(sendText.getText().toString());
            }
        });
        sp_odom_divider = view.findViewById(R.id.sp_odom_divider);
        ArrayAdapter<CharSequence> ad_odom_divider = ArrayAdapter.createFromResource(getActivity(),
                R.array.odom_divider, android.R.layout.simple_spinner_dropdown_item);
        ad_odom_divider.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp_odom_divider.setAdapter(ad_odom_divider);
        btn_set_odom_divider = view.findViewById(R.id.btn_set_odom_divider);
        btn_set_odom_divider.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                switch(sp_odom_divider.getSelectedItemPosition()) {
                    case 0:
                        sendText.setText("AT+ODOMD=8\n");
                        break;
                    case 1:
                        sendText.setText("AT+ODOMD=4\n");
                        break;
                    case 2:
                        sendText.setText("AT+ODOMD=2\n");
                        break;
                    case 3:
                        sendText.setText("AT+ODOMD=1\n");
                        break;
                }
                send(sendText.getText().toString());
            }
        });

        //PWRON_COUNTER
        final String regex_0toFF = "[0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5]";
        btn_get_pwron_counter = view.findViewById(R.id.btn_get_pwron_counter);
        btn_get_pwron_counter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendText.setText("AT+PWRON?\n");
                send(sendText.getText().toString());
            }
        });
        et_pwron_counter = view.findViewById(R.id.et_pwron_counter);
        et_pwron_counter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                validateEditTextStr(et_pwron_counter, regex_0toFF, "(0 a 255)");
            }
        });
        btn_set_pwron_counter = view.findViewById(R.id.btn_set_pwron_counter);
        btn_set_pwron_counter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String PowerOnCntStr = et_pwron_counter.getText().toString();
                if(validateEditTextStr(et_pwron_counter, regex_0toFF, "(0 a 255)")) {
                    sendText.setText("AT+PWRON="+PowerOnCntStr+"\n");
                    send(sendText.getText().toString());
                }
            }
        });

        //RESET
        btn_reset = view.findViewById(R.id.reset);
        btn_reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                tb_connect.setChecked(false);
                tv_version.setText("");
                et_serial.setText("");
                et_sens_cfg.setText("");
                cb_in1_ana.setChecked(false);
                cb_in1_dig.setChecked(false);
                cb_in1_2ana.setChecked(false);
                cb_in1_2dig.setChecked(false);
                cb_in2_ana.setChecked(false);
                cb_in2_dig.setChecked(false);
                cb_out2_st.setChecked(false);
                et_odom_constant.setText("");
                et_odom_value.setText("");
                et_pwron_counter.setText("");
                sendText.setText("AT+RST=1\n");
                send(sendText.getText().toString());
            }
        });
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(localEchoEnabled)
                receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private void receive(byte[] data) {
        Log.d("DATA ", Arrays.toString(data));
        if(hexEnabled) {
            receiveText.append(TextUtil.toHexString(data) + '\n');
        } else {
            String msg = new String(data, StandardCharsets.ISO_8859_1);
            if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg.charAt(0) == '\n') {
                    Editable edt = receiveText.getEditableText();
                    if (edt != null && edt.length() > 1)
                        edt.replace(edt.length() - 2, edt.length(), "");
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
            }
            receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));

            final String _VER_ = "+VER: ";
            if(msg.contains(_VER_)) {
                String versionStr = msg.substring(
                        msg.indexOf(_VER_) + _VER_.length(),
                        msg.indexOf(" | ", msg.indexOf(_VER_)));
                tv_version.setText(versionStr);
            }
            final String _SN_ = "+SN: ";
            if(msg.contains(_SN_)) {
                et_serial.setText(
                        msg.substring(
                                msg.indexOf(_SN_) + _SN_.length(),
                                msg.indexOf(TextUtil.newline_lf, msg.indexOf(_SN_)))
                );
            }
            final String _SENCFG_ = "+SENCFG: ";
            if(msg.contains(_SENCFG_)) {
                byte[] SensorCfgBytes;
                String SensorCfgStr = msg.substring(
                        msg.indexOf(_SENCFG_) + _SENCFG_.length(),
                        msg.indexOf(TextUtil.newline_lf, msg.indexOf(_SENCFG_)));
                SensorCfgBytes = TextUtil.fromHexString(SensorCfgStr);

                getSensorCfgBits(SensorCfgBytes);
                updateSensorCfg();

                et_sens_cfg.setText(String.format("%04X", SensorCfg));
            }
            final String _REMIS_ = "+REMIS: ";
            if(msg.contains(_REMIS_)) {
                if (Byte.parseByte(
                        msg.substring(
                                msg.indexOf(_REMIS_) + _REMIS_.length(),
                                msg.indexOf(TextUtil.newline_lf, msg.indexOf(_REMIS_)))) == 1)
                    sp_busy_source.setSelection(0);
            }
            final String _ODOMC_ = "+ODOMC: ";
            if(msg.contains(_ODOMC_)) {
                et_odom_constant.setText(
                        msg.substring(
                                msg.indexOf(_ODOMC_) + _ODOMC_.length(),
                                msg.indexOf(TextUtil.newline_lf, msg.indexOf(_ODOMC_)))
                );
            }
            final String _ODOMV_ = "+ODOMV: ";
            if(msg.contains(_ODOMV_)) {
                et_odom_value.setText(
                        msg.substring(
                                msg.indexOf(_ODOMV_) + _ODOMV_.length(),
                                msg.indexOf(TextUtil.newline_lf, msg.indexOf(_ODOMV_)))
                );
            }
            final String _ODOMD_ = "+ODOMD: ";
            if(msg.contains(_ODOMD_)) {
                byte OdometerDiv = Byte.parseByte(
                        msg.substring(
                                msg.indexOf(_ODOMD_) + _ODOMD_.length(),
                                msg.indexOf(TextUtil.newline_lf,msg.indexOf(_ODOMD_))));
                switch(OdometerDiv)
                {
                    case 8: sp_odom_divider.setSelection(0); break;
                    case 4: sp_odom_divider.setSelection(1); break;
                    case 2: sp_odom_divider.setSelection(2); break;
                    case 1: sp_odom_divider.setSelection(3); break;
                }
            }
            final String _PWRON_ = "+PWRON: ";
            if(msg.contains(_PWRON_)) {
                et_pwron_counter.setText(
                        msg.substring(
                                msg.indexOf(_PWRON_) + _PWRON_.length(),
                                msg.indexOf(TextUtil.newline_lf,msg.indexOf(_PWRON_)))
                );
            }
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public void onSerialRead(byte[] data) {
        for (byte b:data) {
            data2.add(b);
        }
        if (!timerActive) {
            new CountDownTimer(100, 10) {
                public void onTick(long millisUntilFinished) {
                }
                public void onFinish() {
                    timerActive = false;
                    byte[] dataAux = new byte[data2.size()];
                    for (int i = 0; i < dataAux.length; i++) {
                        dataAux[i] = (byte) data2.get(i);
                    }
                    receive(dataAux);
                    data2.clear();
                }
            }.start();
        }
        Log.d("receiver data  ", Arrays.toString(data));
        Log.d("receiver data2 ", String.valueOf(data2));
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
